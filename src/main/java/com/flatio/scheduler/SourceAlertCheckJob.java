package com.flatio.scheduler;

import com.flatio.domain.alert.AlertType;
import com.flatio.domain.source.Source;
import com.flatio.repository.SourceRepository;
import com.flatio.service.SourceAlertService;
import com.flatio.service.SyncRunService;
import com.flatio.service.notification.SourceAlertNotifier;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically evaluates the two source-health alert rules from issue #419 against
 * {@link SyncRunService}'s recorded run history, for every active source.
 *
 * <p>Deliberately built as a scheduled checker over {@code sync_runs} rather than wired through
 * Prometheus Alertmanager (the alternative the issue named): Alertmanager would need its own new
 * service in {@code docker-compose.yml} and a rules config to maintain, for the same two rules
 * this class already expresses directly against the data those Prometheus metrics (issue #417)
 * are themselves derived from.
 *
 * <p>Runs on a configurable cron via {@code flatio.alerts.cron}
 * (env: {@code FLATIO_ALERTS_CRON}); defaults to every 15 minutes. Each source is checked
 * independently — an error evaluating one source is logged and does not stop the rest.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class SourceAlertCheckJob {

  private final SourceRepository sourceRepository;
  private final SyncRunService syncRunService;
  private final SourceAlertService sourceAlertService;
  private final SourceAlertNotifier notifier;

  @Value("${flatio.alerts.no-success-hours:3}")
  private long noSuccessHours;

  @Value("${flatio.alerts.error-rate-window-runs:5}")
  private int errorRateWindowRuns;

  @Value("${flatio.alerts.error-rate-threshold:0.5}")
  private double errorRateThreshold;

  @Value("${flatio.alerts.cooldown-hours:6}")
  private long cooldownHours;

  /**
   * Evaluates both alert rules for every active source.
   */
  @Scheduled(cron = "${flatio.alerts.cron:0 */15 * * * *}")
  public void checkSources() {
    for (Source source : sourceRepository.findByActiveTrue()) {
      try {
        checkNoSuccessfulSync(source.getCode());
        checkErrorRate(source.getCode());
      } catch (Exception e) {
        log.error("Source alert check failed: source={}, error={}", source.getCode(), e.getMessage(), e);
      }
    }
  }

  private void checkNoSuccessfulSync(String sourceId) {
    if (!syncRunService.hasAnyRun(sourceId)) {
      return;
    }

    var lastSuccessAt = syncRunService.findLastSuccessfulRunAt(sourceId);
    boolean failing = lastSuccessAt.map(this::olderThanThreshold).orElse(true);

    if (failing) {
      String detail = lastSuccessAt
          .map(at -> "Последний успешный синк: " + at + " (" + Duration.between(at, Instant.now()).toHours() + " ч. назад)")
          .orElse("Ни одного успешного синка с момента появления источника.");
      notifyIfShould(sourceId, AlertType.NO_SUCCESSFUL_SYNC, detail);
    } else {
      resolveIfShould(sourceId, AlertType.NO_SUCCESSFUL_SYNC);
    }
  }

  private void checkErrorRate(String sourceId) {
    var failureRate = syncRunService.calculateRecentFailureRate(sourceId, errorRateWindowRuns);
    boolean failing = failureRate.map(rate -> rate > errorRateThreshold).orElse(false);

    if (failing) {
      String detail = String.format("Доля неудачных синков за последние %d запусков: %.0f%% (порог %.0f%%)",
          errorRateWindowRuns, failureRate.get() * 100, errorRateThreshold * 100);
      notifyIfShould(sourceId, AlertType.HIGH_ERROR_RATE, detail);
    } else {
      resolveIfShould(sourceId, AlertType.HIGH_ERROR_RATE);
    }
  }

  private boolean olderThanThreshold(Instant lastSuccessAt) {
    return Duration.between(lastSuccessAt, Instant.now()).toHours() >= noSuccessHours;
  }

  private void notifyIfShould(String sourceId, AlertType alertType, String detail) {
    boolean shouldNotify = sourceAlertService.registerFailure(sourceId, alertType, Duration.ofHours(cooldownHours));
    if (shouldNotify) {
      notifier.sendFailureAlert(sourceId, alertType, detail);
    }
  }

  private void resolveIfShould(String sourceId, AlertType alertType) {
    boolean recovered = sourceAlertService.registerRecovery(sourceId, alertType);
    if (recovered) {
      notifier.sendRecoveryNotice(sourceId, alertType);
    }
  }
}
