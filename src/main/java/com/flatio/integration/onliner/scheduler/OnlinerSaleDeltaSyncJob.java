package com.flatio.integration.onliner.scheduler;

import com.flatio.domain.source.Source;
import com.flatio.domain.source.SyncType;
import com.flatio.integration.core.RawListing;
import com.flatio.integration.onliner.client.OnlinerSaleConnector;
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
 * Periodically fetches only new and recently updated Onliner sale listings (delta sync).
 *
 * <p>The {@code since} cursor is read from {@link SyncRunService#findLastSuccessfulRunAt(String)}
 * on every run — not kept in memory — so it survives application restarts without any in-memory
 * state (issue #369, matching the pattern already used by the Realt and Kufar delta jobs). On the
 * very first execution (before any successful run is recorded) the threshold is
 * {@link Instant#EPOCH}, causing a full page pass — subsequent executions only retrieve listings
 * newer than the previous successful run.
 *
 * <p>Cron schedule is configurable via {@code flatio.sync.onliner-sale.delta.cron}
 * (env: {@code FLATIO_SYNC_ONLINER_SALE_DELTA_CRON}); default is every 15 minutes.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class OnlinerSaleDeltaSyncJob {

  private final OnlinerSaleConnector onlinerSaleConnector;
  private final SourceRepository sourceRepository;
  private final ListingIngestionService listingIngestionService;
  private final SyncRunService syncRunService;

  /**
   * Fetches sale listings newer than the previous successful run timestamp and persists them.
   */
  @Scheduled(cron = "${flatio.sync.onliner-sale.delta.cron}")
  public void runDeltaSync() {
    Instant since = syncRunService.findLastSuccessfulRunAt(onlinerSaleConnector.getSourceId()).orElse(Instant.EPOCH);
    Instant runStart = Instant.now();
    log.info("Onliner sale delta sync started: since={}", since);
    try {
      executeDeltaSync(since, runStart);
    } catch (CallNotPermittedException e) {
      log.warn("Onliner sale delta sync skipped: circuit breaker OPEN");
    } catch (DataAccessException e) {
      log.warn("Onliner sale delta sync: DB unavailable, will retry on next schedule: source={}, error={}",
          onlinerSaleConnector.getSourceId(), e.getMessage());
    } catch (Exception e) {
      log.error("Onliner sale delta sync failed: source={}, error={}", onlinerSaleConnector.getSourceId(), e.getMessage(), e);
      syncRunService.record(SyncRunRequest.failure(
          onlinerSaleConnector.getSourceId(), SyncType.DELTA, runStart, Instant.now()));
    }
  }

  private void executeDeltaSync(Instant since, Instant runStart) {
    Source source = sourceRepository.findByCode(onlinerSaleConnector.getSourceId())
        .orElseThrow(() -> new IllegalStateException(
            "Source not registered in DB: " + onlinerSaleConnector.getSourceId()));
    if (!source.isActive()) {
      log.debug("Onliner sale delta sync skipped: source disabled: source={}", onlinerSaleConnector.getSourceId());
      return;
    }

    List<RawListing> rawListings = onlinerSaleConnector.fetchDelta(since);
    if (rawListings.isEmpty()) {
      recordEmptyRun(since, runStart);
      return;
    }
    ingestAndRecord(rawListings, source, runStart);
  }

  private void recordEmptyRun(Instant since, Instant runStart) {
    log.info("Onliner sale delta sync: no new listings since={}", since);
    Instant finish = Instant.now();
    syncRunService.record(SyncRunRequest.success(
        onlinerSaleConnector.getSourceId(), SyncType.DELTA, runStart, finish, 0,
        new BatchIngestResult(0, 0, 0)));
  }

  private void ingestAndRecord(List<RawListing> rawListings, Source source, Instant runStart) {
    BatchIngestResult result = listingIngestionService.ingestBatch(rawListings, source);
    Instant finish = Instant.now();
    long durationMs = Duration.between(runStart, finish).toMillis();

    syncRunService.record(SyncRunRequest.success(
        onlinerSaleConnector.getSourceId(), SyncType.DELTA, runStart, finish,
        rawListings.size(), result));

    log.info("Onliner sale delta sync completed: fetched={}, added={}, updated={}, errors={}, durationMs={}",
        rawListings.size(), result.added(), result.updated(), result.errors(), durationMs);
  }
}
