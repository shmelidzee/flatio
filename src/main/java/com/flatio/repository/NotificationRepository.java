package com.flatio.repository;

import com.flatio.domain.listing.Listing;
import com.flatio.domain.notification.Notification;
import com.flatio.domain.subscription.Subscription;
import com.flatio.domain.subscription.TriggerType;
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
}
