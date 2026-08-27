package com.flatio.integration.onliner.scheduler;

import com.flatio.domain.source.Source;
import com.flatio.domain.source.SyncType;
import com.flatio.integration.core.RawListing;
import com.flatio.integration.onliner.client.OnlinerConnector;
import com.flatio.repository.SourceRepository;
import com.flatio.service.ListingIngestionService;
import com.flatio.service.SyncRunService;
import com.flatio.service.domain.BatchIngestResult;
import com.flatio.service.domain.SyncRunRequest;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically fetches only new and recently updated Onliner listings (delta sync).
 *
 * <p>The {@code since} cursor is read from {@link SyncRunService#findLastSuccessfulRunAt(String)}
 * on every run — not kept in memory — so it survives application restarts without any in-memory
 * state (issue #369, matching the pattern already used by the Realt and Kufar delta jobs). On the
 * very first execution (before any successful run is recorded) the threshold is
 * {@link Instant#EPOCH}, causing a full page pass — subsequent executions only retrieve listings
 * newer than the previous successful run.
 *
 * <p>Cron schedule is configurable via {@code flatio.sync.onliner.delta.cron}
 * (env: {@code FLATIO_SYNC_ONLINER_DELTA_CRON}); default is every 10 minutes.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class OnlinerDeltaSyncJob {

  private final OnlinerConnector onlinerConnector;
  private final SourceRepository sourceRepository;
  private final ListingIngestionService listingIngestionService;
  private final SyncRunService syncRunService;

  /**
   * Fetches listings newer than the previous successful run timestamp and persists them.
   */
  @Scheduled(cron = "${flatio.sync.onliner.delta.cron}")
  public void runDeltaSync() {
    Instant since = syncRunService.findLastSuccessfulRunAt(onlinerConnector.getSourceId()).orElse(Instant.EPOCH);
    Instant runStart = Instant.now();
    log.info("Onliner delta sync started: since={}", since);
    try {
      executeDeltaSync(since, runStart);
    } catch (CallNotPermittedException e) {
      log.warn("Onliner delta sync skipped: circuit breaker OPEN");
    } catch (DataAccessException e) {
      log.warn("Onliner delta sync: DB unavailable, will retry on next schedule: source={}, error={}",
          onlinerConnector.getSourceId(), e.getMessage());
    } catch (Exception e) {
      log.error("Onliner delta sync failed: source={}, error={}", onlinerConnector.getSourceId(), e.getMessage(), e);
      syncRunService.record(SyncRunRequest.failure(
          onlinerConnector.getSourceId(), SyncType.DELTA, runStart, Instant.now()));
    }
  }

  private void executeDeltaSync(Instant since, Instant runStart) {
    Source source = sourceRepository.findByCode(onlinerConnector.getSourceId())
        .orElseThrow(() -> new IllegalStateException(
            "Source not registered in DB: " + onlinerConnector.getSourceId()));
    if (!source.isActive()) {
      log.debug("Onliner delta sync skipped: source disabled: source={}", onlinerConnector.getSourceId());
      return;
    }

    List<RawListing> rawListings = onlinerConnector.fetchDelta(since);
    if (rawListings.isEmpty()) {
      recordEmptyRun(since, runStart);
      return;
    }
    ingestAndRecord(rawListings, source, runStart);
  }

  private void recordEmptyRun(Instant since, Instant runStart) {
    log.info("Onliner delta sync: no new listings since={}", since);
    Instant finish = Instant.now();
    syncRunService.record(SyncRunRequest.success(
        onlinerConnector.getSourceId(), SyncType.DELTA, runStart, finish, 0,
        new BatchIngestResult(0, 0, 0)));
  }

  private void ingestAndRecord(List<RawListing> rawListings, Source source, Instant runStart) {
    BatchIngestResult result = listingIngestionService.ingestBatch(rawListings, source);
    Instant finish = Instant.now();
    long durationMs = Duration.between(runStart, finish).toMillis();

    syncRunService.record(SyncRunRequest.success(
        onlinerConnector.getSourceId(), SyncType.DELTA, runStart, finish,
        rawListings.size(), result));

    log.info("Onliner delta sync completed: fetched={}, added={}, updated={}, errors={}, durationMs={}",
        rawListings.size(), result.added(), result.updated(), result.errors(), durationMs);
  }
}
