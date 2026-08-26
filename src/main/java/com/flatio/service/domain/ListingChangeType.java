package com.flatio.service.domain;

/**
 * Kind of change observed on a listing during a sync.
 *
 * <p>Drives which {@code com.flatio.domain.subscription.TriggerType} candidates
 * {@code NotificationTriggerService} considers for a given {@link ListingChange}.
 */
public enum ListingChangeType {
  NEW,
  UPDATED
}
