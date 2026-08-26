package com.flatio.service.impl;

import com.flatio.domain.listing.Listing;
import com.flatio.domain.notification.Notification;
import com.flatio.domain.notification.NotificationStatus;
import com.flatio.domain.subscription.Subscription;
import com.flatio.domain.subscription.TriggerType;
import com.flatio.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists a single {@link Notification} in its own transaction, isolated from the caller's.
 *
 * <p>{@link NotificationTriggerServiceImpl#evaluate} runs an entire batch of notification
 * candidates for a set of listing changes inside one {@code @Transactional} method. If a
 * notification were inserted directly there, a {@code DataIntegrityViolationException} raised by
 * a concurrent {@code evaluate} run racing on the same (subscription, listing, triggerType)
 * unique constraint would mark that whole surrounding transaction rollback-only, discarding every
 * other notification already created in the same batch. Running each insert through a dedicated
 * bean with its own {@link Propagation#REQUIRES_NEW} transaction confines such a failure to the
 * single candidate that raced, letting the caller catch the exception and continue with the rest
 * of the batch.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class NotificationCreator {

  private final NotificationRepository notificationRepository;

  /**
   * Creates a PENDING notification for the given subscription/listing/trigger combination in a
   * new, independent transaction.
   *
   * @param subscription the subscription to notify
   * @param listing      the listing the notification is about
   * @param triggerType  the event that raised the notification
   * @throws org.springframework.dao.DataIntegrityViolationException if a concurrent call already
   *     created a notification for the same (subscription, listing, triggerType) combination
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void create(Subscription subscription, Listing listing, TriggerType triggerType) {
    Notification notification = new Notification();
    notification.setSubscription(subscription);
    notification.setListing(listing);
    notification.setTriggerType(triggerType);
    notification.setStatus(NotificationStatus.PENDING);
    notificationRepository.save(notification);
    log.info("Notification created: subscriptionId={}, listingId={}, triggerType={}",
        subscription.getId(), listing.getId(), triggerType);
  }
}
