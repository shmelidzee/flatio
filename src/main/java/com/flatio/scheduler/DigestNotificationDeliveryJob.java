package com.flatio.scheduler;

import com.flatio.service.notification.BatchNotificationSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically delivers {@code PENDING} DIGEST (and rate-limited REALTIME overflow) notifications
 * over Telegram via {@link BatchNotificationSender#sendDigest()} (issue #410, FR-SUB-6).
 *
 * <p>Cron schedule is configurable via {@code flatio.notifications.digest.cron}
 * (env: {@code FLATIO_NOTIFICATIONS_DIGEST_CRON}); default is every 3 hours.
 *
 * <p>A failure reading or delivering the batch does not abort the scheduler or leak into the
 * next trigger; each notification's own delivery failure is already isolated and recorded by
 * {@link BatchNotificationSender}.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class DigestNotificationDeliveryJob {

  private final BatchNotificationSender batchNotificationSender;

  /**
   * Triggers one digest delivery run.
   */
  @Scheduled(cron = "${flatio.notifications.digest.cron:0 0 */3 * * *}")
  public void runDigestDelivery() {
    try {
      batchNotificationSender.sendDigest();
    } catch (Exception e) {
      log.warn("Digest notification delivery run skipped: error={}", e.getMessage(), e);
    }
  }
}
