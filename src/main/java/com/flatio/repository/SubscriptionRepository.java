package com.flatio.repository;

import com.flatio.domain.subscription.Subscription;
import com.flatio.domain.user.User;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {

  /**
   * Returns a page of subscriptions owned by the given user.
   *
   * @param user     the owning user
   * @param pageable pagination and sorting configuration
   * @return page of subscriptions, never null
   */
  Page<Subscription> findByUser(User user, Pageable pageable);

  /**
   * Returns all active subscriptions, regardless of owner.
   *
   * <p>Used by {@code NotificationTriggerServiceImpl} to evaluate every enabled subscription
   * against a batch of listing changes; paused subscriptions ({@code isActive = false}) are
   * excluded from evaluation entirely.
   *
   * @return list of active subscriptions, never null, may be empty
   */
  List<Subscription> findByActiveTrue();

  /**
   * Finds a subscription by ID, scoped to the given owner.
   *
   * <p>Used to enforce that a user can only access their own subscriptions without leaking
   * the existence of subscriptions belonging to other users.
   *
   * @param id   the subscription ID
   * @param user the expected owner
   * @return the subscription if found and owned by {@code user}, or empty
   */
  Optional<Subscription> findByIdAndUser(Long id, User user);

  /**
   * Counts the active subscriptions owned by the given user.
   *
   * @param user the owning user
   * @return number of active subscriptions
   */
  long countByUserAndActiveTrue(User user);
}
