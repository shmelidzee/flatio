package com.flatio.scheduler;

import com.flatio.integration.onliner.scheduler.OnlinerDeltaSyncJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Status;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled watchdog that triggers an unplanned delta sync when data freshness is {@code DOWN}.
 *
 * <p>Runs on a configurable cron via {@code flatio.health.freshness.watchdog.cron}
 * (env: {@code FLATIO_HEALTH_FRESHNESS_WATCHDOG_CRON}); defaults to every 15 minutes.
 *
 * <p>Delegates to {@link OnlinerDeltaSyncJob#runDeltaSync()} so that all existing
 * rate-limit, retry, and circuit-breaker protections apply to the triggered sync.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class DataFreshnessWatchdog {

  private final DataFreshnessHealthIndicator healthIndicator;
  private final OnlinerDeltaSyncJob onlinerDeltaSyncJob;

  /**
   * Checks data freshness and triggers a delta sync if the health indicator reports DOWN.
   */
  @Scheduled(cron = "${flatio.health.freshness.watchdog.cron:0 */15 * * * *}")
  public void checkAndHeal() {
    if (Status.DOWN.equals(healthIndicator.health().getStatus())) {
      log.warn("DataFreshness watchdog: health DOWN — triggering unplanned delta sync");
      onlinerDeltaSyncJob.runDeltaSync();
    }
  }
}
