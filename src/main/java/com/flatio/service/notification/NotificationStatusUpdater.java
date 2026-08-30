package com.flatio.service.notification;

import com.flatio.domain.notification.Notification;
import com.flatio.domain.notification.NotificationStatus;
import com.flatio.repository.NotificationRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Applies the terminal delivery outcome (SENT/FAILED) to a {@link Notification} and records the
 * corresponding metric.
 *
 * <p>Extracted so {@code TelegramNotificationSender} (REALTIME) and {@code
 * BatchNotificationSender} (DIGEST/DAILY, issue #410) record identical {@code
 * flatio.notifications.sent}/{@code flatio.notifications.failed} metrics regardless of which
 * delivery mode produced the outcome.
 */
@Component
@RequiredArgsConstructor
public class NotificationStatusUpdater {

  private final NotificationRepository notificationRepository;
  private final MeterRegistry meterRegistry;

  /**
   * Marks the notification delivered and records the {@code sent} metric.
   *
   * @param notification the notification that was successfully delivered, never null
   */
  public void markSent(Notification notification) {
    notification.setStatus(NotificationStatus.SENT);
    notification.setSentAt(Instant.now());
    notificationRepository.save(notification);
    meterRegistry.counter("flatio.notifications.sent", "triggerType", notification.getTriggerType().name())
        .increment();
  }

  /**
   * Marks the notification as failed and records the {@code failed} metric.
   *
   * @param notification the notification that could not be delivered, never null
   */
  public void markFailed(Notification notification) {
    notification.setStatus(NotificationStatus.FAILED);
    notificationRepository.save(notification);
    meterRegistry.counter("flatio.notifications.failed", "triggerType", notification.getTriggerType().name())
        .increment();
  }
}
