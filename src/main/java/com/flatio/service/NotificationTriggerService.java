package com.flatio.service;

import com.flatio.service.domain.ListingChange;
import java.util.List;

/**
 * Evaluates listing changes observed during a sync against active subscriptions and raises
 * PENDING {@code Notification} rows for every match (M2.3.2, FR-SUB-4, FR-SUB-9).
 */
public interface NotificationTriggerService {

  /**
   * Evaluates a batch of listing changes against every active subscription.
   *
   * <p>Runs asynchronously so it never blocks the calling sync/parser thread. A failure while
   * evaluating one change is logged and does not stop evaluation of the remaining changes in the
   * batch. Paused subscriptions ({@code isActive = false}) are ignored.
   *
   * @param changes listing changes observed during the sync, never null
   */
  void evaluate(List<ListingChange> changes);
}
