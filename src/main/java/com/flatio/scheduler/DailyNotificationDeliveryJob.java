package com.flatio.scheduler;

import com.flatio.service.notification.BatchNotificationSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Delivers {@code PENDING} DAILY notifications over Telegram once a day via
 * {@link BatchNotificationSender#sendDaily()} (issue #410, FR-SUB-6).
 *
 * <p>Cron schedule is configurable via {@code flatio.notifications.daily.cron}
 * (env: {@code FLATIO_NOTIFICATIONS_DAILY_CRON}); default is 09:00 Europe/Minsk server time.
 *
 * <p>A failure reading or delivering the batch does not abort the scheduler or leak into the
 * next trigger; each notification's own delivery failure is already isolated and recorded by
 * {@link BatchNotificationSender}.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class DailyNotificationDeliveryJob {

  private final BatchNotificationSender batchNotificationSender;

  /**
   * Triggers one daily delivery run.
   */
  @Scheduled(cron = "${flatio.notifications.daily.cron:0 0 9 * * *}")
  public void runDailyDelivery() {
    try {
      batchNotificationSender.sendDaily();
    } catch (Exception e) {
      log.warn("Daily notification delivery run skipped: error={}", e.getMessage(), e);
    }
  }
}
