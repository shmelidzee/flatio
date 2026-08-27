package com.flatio.scheduler;

import com.flatio.service.notification.TelegramNotificationSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically delivers {@code PENDING} (and retry-eligible {@code FAILED}) notifications over
 * Telegram via {@link TelegramNotificationSender}.
 *
 * <p>Cron schedule is configurable via {@code flatio.notifications.realtime.cron}
 * (env: {@code FLATIO_NOTIFICATIONS_REALTIME_CRON}); default is every minute — REALTIME
 * subscriptions are expected to be delivered close to the moment {@code NotificationTriggerService}
 * raises them.
 *
 * <p>A failure reading or delivering the batch does not abort the scheduler or leak into the
 * next trigger; each notification's own delivery failure is already isolated and recorded by
 * {@link TelegramNotificationSender} (status {@code FAILED}, retried on a later run).
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class NotificationDeliveryJob {

  private final TelegramNotificationSender telegramNotificationSender;

  /**
   * Triggers one delivery run.
   */
  @Scheduled(cron = "${flatio.notifications.realtime.cron:0 * * * * *}")
  public void runDelivery() {
    try {
      telegramNotificationSender.sendPending();
    } catch (Exception e) {
      log.warn("Notification delivery run skipped: error={}", e.getMessage(), e);
    }
  }
}
