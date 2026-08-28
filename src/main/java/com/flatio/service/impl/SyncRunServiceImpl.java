package com.flatio.service.impl;

import com.flatio.domain.source.SyncRun;
import com.flatio.domain.source.SyncRunStatus;
import com.flatio.repository.SyncRunRepository;
import com.flatio.service.SyncRunService;
import com.flatio.service.domain.SyncRunRequest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@Slf4j
@RequiredArgsConstructor
public class SyncRunServiceImpl implements SyncRunService {

  private static final String METRIC_SYNC_DURATION = "flatio.sync.duration";
  private static final String METRIC_SYNC_RUNS = "flatio.sync.runs";

  private final SyncRunRepository syncRunRepository;
  private final MeterRegistry meterRegistry;

  @Override
  @Transactional
  public void record(SyncRunRequest request) {
    SyncRun run = new SyncRun();
    run.setSourceId(request.sourceId());
    run.setSyncType(request.syncType());
    run.setStatus(request.status());
    run.setStartedAt(request.startedAt());
    run.setFinishedAt(request.finishedAt());
    run.setListingsFetched(request.listingsFetched());
    run.setListingsAdded(request.listingsAdded());
    run.setListingsUpdated(request.listingsUpdated());
    run.setListingsErrors(request.listingsErrors());
    syncRunRepository.save(run);
    recordMetrics(request);
    log.debug("Sync run recorded: source={}, type={}, status={}, finishedAt={}",
        request.sourceId(), request.syncType(), request.status(), request.finishedAt());
  }

  /**
   * Publishes per-source sync duration and outcome to Micrometer (issue #417), so every connector
   * sync job is covered without instrumenting each of them individually — they all funnel through
   * this single {@link #record} method.
   *
   * @param request the run just persisted
   */
  private void recordMetrics(SyncRunRequest request) {
    Duration duration = Duration.between(request.startedAt(), request.finishedAt());
    Timer.builder(METRIC_SYNC_DURATION)
        .description("Duration of a connector sync run")
        .tag("source", request.sourceId())
        .tag("syncType", request.syncType().name())
        .tag("status", request.status().name())
        .register(meterRegistry)
        .record(duration);
    Counter.builder(METRIC_SYNC_RUNS)
        .description("Number of connector sync runs")
        .tag("source", request.sourceId())
        .tag("syncType", request.syncType().name())
        .tag("status", request.status().name())
        .register(meterRegistry)
        .increment();
  }

  @Override
  public Optional<Instant> findLastSuccessfulRunAt() {
    return syncRunRepository
        .findTopByStatusOrderByFinishedAtDesc(SyncRunStatus.SUCCESS)
        .map(SyncRun::getFinishedAt);
  }

  @Override
  public Optional<Instant> findLastSuccessfulRunAt(String sourceId) {
    return syncRunRepository
        .findTopBySourceIdAndStatusOrderByFinishedAtDesc(sourceId, SyncRunStatus.SUCCESS)
        .map(SyncRun::getFinishedAt);
  }

  @Override
  @Transactional
  public void cleanupOldRuns(int keepPerSource) {
    int deleted = syncRunRepository.deleteOldRunsBeyondLimitForAllSources(keepPerSource);
    if (deleted > 0) {
      log.debug("Sync run cleanup: deleted={}, keptPerSource={}", deleted, keepPerSource);
    }
  }
}
