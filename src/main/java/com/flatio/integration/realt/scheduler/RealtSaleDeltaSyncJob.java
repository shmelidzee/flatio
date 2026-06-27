package com.flatio.integration.realt.scheduler;

import com.flatio.domain.source.Source;
import com.flatio.domain.source.SyncType;
import com.flatio.integration.core.RawListing;
import com.flatio.integration.realt.client.RealtSaleConnector;
import com.flatio.repository.SourceRepository;
import com.flatio.service.ListingIngestionService;
import com.flatio.service.SyncRunService;
import com.flatio.service.domain.BatchIngestResult;
import com.flatio.service.domain.SyncRunRequest;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically fetches only new Realt.by sale listings since the last successful sync (delta sync).
 *
 * <p>On each execution the most recent successful {@code SyncRun} for the RealtSale source is
 * retrieved from the database. If a prior run exists, only listings published after that run's
 * finish time are fetched ({@link RealtSaleConnector#fetchDelta(Instant)}). If no successful run
 * exists yet (first boot or after a prolonged outage), a full crawl is performed instead and
 * the run is recorded as {@link SyncType#FULL}.
 *
 * <p>Because the cursor is read from the database, the delta position survives application
 * restarts without any in-memory state.
 *
 * <p>Cron schedule is configurable via {@code flatio.sync.realt-sale.delta.cron}
 * (env: {@code FLATIO_SYNC_REALT_SALE_DELTA_CRON}); default is every 15 minutes.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class RealtSaleDeltaSyncJob {

  private final RealtSaleConnector realtSaleConnector;
  private final SourceRepository sourceRepository;
  private final ListingIngestionService listingIngestionService;
  private final SyncRunService syncRunService;

  /**
   * Injected lazily to avoid circular-dependency issues; null-safe usage via {@link #fullSyncIsRunning()}.
   * Used to skip the full-sync fallback when {@link RealtSaleFullSyncJob} is still executing.
   */
  @Lazy
  @Autowired
  private RealtSaleFullSyncJob realtSaleFullSyncJob;

  /**
   * Determines sync mode from the last successful run and fetches accordingly.
   *
   * <p>The delta cursor is not updated on failure to avoid skipping listings after a
   * transient error — the next scheduled execution will re-read the last successful run.
   */
  @Scheduled(cron = "${flatio.sync.realt-sale.delta.cron}")
  public void runDeltaSync() {
    Instant runStart = Instant.now();
    try {
      Source source = sourceRepository.findByCode(realtSaleConnector.getSourceId())
          .orElseThrow(() -> new IllegalStateException(
              "Source not registered in DB: " + realtSaleConnector.getSourceId()));

      Optional<Instant> lastRunAt = syncRunService.findLastSuccessfulRunAt(realtSaleConnector.getSourceId());

      if (lastRunAt.isPresent()) {
        performDeltaSync(source, lastRunAt.get(), runStart);
      } else if (fullSyncIsRunning()) {
        log.info("RealtSale delta sync: FullSyncJob is already running, skipping fallback: source={}",
            realtSaleConnector.getSourceId());
      } else {
        log.info("RealtSale: no prior successful run found — falling back to full sync");
        performFullSyncFallback(source, runStart);
      }
    } catch (CallNotPermittedException e) {
      log.warn("RealtSale delta sync skipped: circuit breaker OPEN");
    } catch (DataAccessException e) {
      log.warn("RealtSale delta sync: DB unavailable, will retry on next schedule: source={}, error={}",
          realtSaleConnector.getSourceId(), e.getMessage());
    } catch (Exception e) {
      log.error("RealtSale delta sync failed: source={}, error={}",
          realtSaleConnector.getSourceId(), e.getMessage(), e);
      syncRunService.record(SyncRunRequest.failure(
          realtSaleConnector.getSourceId(), SyncType.DELTA, runStart, Instant.now()));
    }
  }

  private boolean fullSyncIsRunning() {
    return realtSaleFullSyncJob != null && realtSaleFullSyncJob.isRunning();
  }

  private void performDeltaSync(Source source, Instant since, Instant runStart) {
    log.info("RealtSale delta sync started: since={}", since);
    List<RawListing> rawListings = realtSaleConnector.fetchDelta(since);

    if (rawListings.isEmpty()) {
      log.info("RealtSale delta sync: no new listings since={}", since);
      Instant finish = Instant.now();
      syncRunService.record(SyncRunRequest.success(
          realtSaleConnector.getSourceId(), SyncType.DELTA, runStart, finish, 0,
          new BatchIngestResult(0, 0, 0)));
      return;
    }

    BatchIngestResult result = listingIngestionService.ingestBatch(rawListings, source);
    Instant finish = Instant.now();
    long durationMs = Duration.between(runStart, finish).toMillis();

    syncRunService.record(SyncRunRequest.success(
        realtSaleConnector.getSourceId(), SyncType.DELTA, runStart, finish,
        rawListings.size(), result));

    log.info("RealtSale delta sync completed: fetched={}, added={}, updated={}, errors={}, durationMs={}",
        rawListings.size(), result.added(), result.updated(), result.errors(), durationMs);
  }

  private void performFullSyncFallback(Source source, Instant runStart) {
    List<RawListing> rawListings = realtSaleConnector.fetch();

    if (rawListings.isEmpty()) {
      log.warn("RealtSale full-sync fallback: fetch returned empty list — skipping to avoid data loss");
      return;
    }

    BatchIngestResult result = listingIngestionService.ingestBatch(rawListings, source);

    Set<String> activeExternalIds = rawListings.stream()
        .map(RawListing::externalId)
        .collect(Collectors.toSet());
    listingIngestionService.applyMissedSyncPenalty(source, activeExternalIds);

    Instant finish = Instant.now();
    long durationMs = Duration.between(runStart, finish).toMillis();

    syncRunService.record(SyncRunRequest.success(
        realtSaleConnector.getSourceId(), SyncType.FULL, runStart, finish,
        rawListings.size(), result));

    log.info("RealtSale full-sync fallback completed: fetched={}, added={}, updated={}, errors={}, durationMs={}",
        rawListings.size(), result.added(), result.updated(), result.errors(), durationMs);
  }
}
