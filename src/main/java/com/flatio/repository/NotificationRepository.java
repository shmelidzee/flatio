package com.flatio.repository;

import com.flatio.domain.listing.Listing;
import com.flatio.domain.notification.Notification;
import com.flatio.domain.subscription.Subscription;
import com.flatio.domain.subscription.TriggerType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

  /**
   * Finds an existing notification for the given subscription, listing and trigger type.
   *
   * <p>Used to enforce deduplication (FR-SUB-8) in application code before insert, ahead of the
   * database-level unique constraint that guards against a concurrent duplicate insert.
   *
   * @param subscription the subscription the notification belongs to
   * @param listing      the listing the notification is about
   * @param triggerType  the event that raised the notification
   * @return the existing notification if one was already created, or empty
   */
  Optional<Notification> findBySubscriptionAndListingAndTriggerType(
      Subscription subscription, Listing listing, TriggerType triggerType);

  /**
   * Finds existing notifications matching any combination of the given subscriptions, listings
   * and trigger types.
   *
   * <p>Used to batch-load already-created notifications ahead of a change-evaluation run instead
   * of issuing one {@code findBySubscriptionAndListingAndTriggerType} query per candidate. The
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
}
