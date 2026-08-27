package com.flatio.repository;

import com.flatio.domain.listing.Listing;
import com.flatio.domain.notification.Notification;
import com.flatio.domain.notification.NotificationStatus;
import com.flatio.domain.subscription.DeliveryMode;
import com.flatio.domain.subscription.Subscription;
import com.flatio.domain.subscription.TriggerType;
import com.flatio.domain.user.User;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

  /**
   * Finds existing notifications matching any combination of the given subscriptions, listings
   * and trigger types.
   *
   * <p>Used to batch-load already-created notifications ahead of a change-evaluation run instead
   * of issuing one lookup query per candidate (FR-SUB-8 deduplication), ahead of the
   * database-level unique constraint that guards against a concurrent duplicate insert. The
   * result is a superset of the caller's actual candidate triples (it does not enforce that
   * subscription, listing and trigger type belong together in the same row) — the caller narrows
   * it down in memory to the specific combinations it cares about.
   *
   * @param subscriptions candidate subscriptions, must not be empty
   * @param listings      candidate listings, must not be empty
   * @param triggerTypes  candidate trigger types, must not be empty
   * @return existing notifications whose subscription, listing and trigger type are each in the
   *     given collections, never null
   */
  List<Notification> findBySubscriptionInAndListingInAndTriggerTypeIn(
      Collection<Subscription> subscriptions, Collection<Listing> listings, Collection<TriggerType> triggerTypes);

  /**
   * Finds notifications ready to be (re)delivered over Telegram: belonging to a REALTIME
   * subscription of an active user, still {@code PENDING}, or {@code FAILED} and old enough to
   * retry.
   *
   * <p>Restricted to {@code deliveryMode = REALTIME} at the query level — not just filtered in
   * the caller — so that DIGEST/DAILY notifications (unsendable until issue #410 exists) never
   * occupy a batch slot ahead of a REALTIME one. Without this, a backlog of DIGEST/DAILY
   * {@code PENDING} rows (oldest-first) would eventually starve REALTIME delivery entirely once
   * that backlog exceeds the page size.
   *
   * <p>Likewise restricted to {@code s.user.active = true} at the query level (issue #438):
   * deactivating a user (see {@code AdminUserServiceImpl#update}) must stop Telegram delivery the
   * same way it already revokes JWT access (issue #365), without deleting the pending row —
   * excluding it here (rather than deleting or filtering post-fetch) means a reactivated user's
   * backlog is picked up automatically by the next run, and a deactivated user's backlog never
   * displaces an active user's notification from a batch slot.
   *
   * <p>Batches {@code subscription}, {@code subscription.user}, {@code listing}, its
   * {@code source} and {@code currency} in one query via {@code JOIN FETCH} — {@code
   * TelegramNotificationSender} needs all of them (recipient chat ID, rate-limit key, and the
   * listing card itself) for every row it processes, so fetching them eagerly here avoids N+1
   * lazy-load queries across the batch.
   *
   * @param deliveryMode only subscriptions with this delivery mode are considered
   * @param pending      the {@code PENDING} status constant
   * @param failed       the {@code FAILED} status constant
   * @param retryBefore  a {@code FAILED} notification is only retried once its last update is at
   *                     or before this instant
   * @param pageable     caps how many notifications one run processes
   * @return sendable notifications, oldest first, never null
   */
  @Query("SELECT n FROM Notification n "
      + "JOIN FETCH n.subscription s "
      + "JOIN FETCH s.user u "
      + "JOIN FETCH n.listing l "
      + "JOIN FETCH l.source "
      + "JOIN FETCH l.currency "
      + "WHERE s.deliveryMode = :deliveryMode "
      + "AND u.active = true "
      + "AND (n.status = :pending OR (n.status = :failed AND n.updatedAt <= :retryBefore)) "
      + "ORDER BY n.createdAt ASC")
  List<Notification> findSendable(
      @Param("deliveryMode") DeliveryMode deliveryMode,
      @Param("pending") NotificationStatus pending,
      @Param("failed") NotificationStatus failed,
      @Param("retryBefore") Instant retryBefore,
      Pageable pageable);

  /**
   * Counts notifications successfully sent to the given user since the given instant, across all
   * of their subscriptions.
   *
   * <p>Backs the per-user real-time delivery rate limit (FR-SUB-9): a user who has already
   * reached the hourly cap has their remaining {@code PENDING} notifications skipped for this
   * run rather than sent, until the digest delivery mechanism (issue #410) picks them up.
   *
   * @param user  the notification recipient
   * @param sent  the {@code SENT} status constant
   * @param since only notifications sent at or after this instant are counted
   * @return number of notifications sent to the user since {@code since}
   */
  @Query("SELECT COUNT(n) FROM Notification n "
      + "WHERE n.subscription.user = :user AND n.status = :sent AND n.sentAt >= :since")
  long countByUserAndStatusAndSentAtAfter(
      @Param("user") User user, @Param("sent") NotificationStatus sent, @Param("since") Instant since);
}
