package com.flatio.scheduler;

import com.flatio.service.SyncRunService;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Spring Boot {@link HealthIndicator} that reports {@code DOWN} when no successful
 * sync run has been recorded within the configured freshness threshold.
 *
 * <p>Exposed under {@code GET /actuator/health} as component {@code dataFreshness}.
 * Details (last sync timestamp, age in minutes) are visible to authorised users only,
 * controlled by {@code management.endpoint.health.show-details: when-authorized}.
 *
 * <p>Threshold is configurable via {@code flatio.health.freshness.threshold-minutes}
 * (env: {@code FLATIO_HEALTH_FRESHNESS_THRESHOLD_MINUTES}); defaults to 30 minutes.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class DataFreshnessHealthIndicator implements HealthIndicator {

  private final SyncRunService syncRunService;

  @Value("${flatio.health.freshness.threshold-minutes:30}")
  private long thresholdMinutes;

  /**
   * Checks the age of the most recent successful sync run.
   *
   * <p>Returns {@code UNKNOWN} when no sync has ever completed — this is expected on first boot
   * while the initial sync is still running, and must not cause a deployment healthcheck failure.
   * Returns {@code DOWN} only when a prior sync exists but has exceeded the freshness threshold.
   *
   * @return {@code UP} if a successful sync finished within the threshold,
   *         {@code UNKNOWN} if no sync has run yet (initial boot),
   *         {@code DOWN} if the last sync is older than the threshold
   */
  @Override
  public Health health() {
    Optional<Instant> lastSuccessfulAt = syncRunService.findLastSuccessfulRunAt();

    if (lastSuccessfulAt.isEmpty()) {
      log.info("Data freshness: no sync runs recorded yet — initial sync pending");
      return Health.unknown()
          .withDetail("reason", "Initial sync pending — no successful sync runs recorded yet")
          .build();
    }

    Instant lastSync = lastSuccessfulAt.get();
    long ageMinutes = Duration.between(lastSync, Instant.now()).toMinutes();

    if (ageMinutes > thresholdMinutes) {
      log.warn("Data freshness alert: lastSync={}, ageMinutes={}", lastSync, ageMinutes);
      return Health.down()
          .withDetail("lastSync", lastSync.toString())
          .withDetail("ageMinutes", ageMinutes)
          .withDetail("thresholdMinutes", thresholdMinutes)
          .build();
    }

    return Health.up()
        .withDetail("lastSync", lastSync.toString())
        .withDetail("ageMinutes", ageMinutes)
        .build();
  }
}
