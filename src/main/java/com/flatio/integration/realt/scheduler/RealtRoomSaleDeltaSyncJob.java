package com.flatio.integration.realt.scheduler;

import com.flatio.domain.source.Source;
import com.flatio.domain.source.SyncType;
import com.flatio.integration.core.RawListing;
import com.flatio.integration.realt.client.RealtRoomSaleConnector;
import com.flatio.service.ListingIngestionService;
import com.flatio.service.SourceService;
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
 * Periodically fetches only new Realt.by room sale listings since the last successful sync.
 *
 * <p>Falls back to a full crawl when no prior successful run exists in the database.
 *
 * <p>Cron schedule is configurable via {@code flatio.sync.realt-room-sale.delta.cron}
 * (env: {@code FLATIO_SYNC_REALT_ROOM_SALE_DELTA_CRON}); default is every 20 minutes.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class RealtRoomSaleDeltaSyncJob {

  private final RealtRoomSaleConnector realtRoomSaleConnector;
  private final SourceService sourceService;
  private final ListingIngestionService listingIngestionService;
  private final SyncRunService syncRunService;

  @Lazy
  @Autowired
  private RealtRoomSaleFullSyncJob realtRoomSaleFullSyncJob;

  /**
   * Determines sync mode from the last successful run and fetches accordingly.
   */
  @Scheduled(cron = "${flatio.sync.realt-room-sale.delta.cron}")
  public void runDeltaSync() {
    Instant runStart = Instant.now();
    try {
      Source source = sourceService.findByCodeOrThrow(realtRoomSaleConnector.getSourceId());
      if (!source.isActive()) {
        log.debug("RealtRoomSale delta sync skipped: source disabled: source={}", realtRoomSaleConnector.getSourceId());
        return;
      }
      Optional<Instant> lastRunAt = syncRunService.findLastSuccessfulRunAt(realtRoomSaleConnector.getSourceId());

      if (lastRunAt.isPresent()) {
        performDeltaSync(source, lastRunAt.get(), runStart);
      } else if (fullSyncIsRunning()) {
        log.info("RealtRoomSale delta sync: FullSyncJob is already running, skipping fallback: source={}",
            realtRoomSaleConnector.getSourceId());
      } else {
        log.info("RealtRoomSale: no prior successful run found — falling back to full sync");
        performFullSyncFallback(source, runStart);
      }
    } catch (CallNotPermittedException e) {
      log.warn("RealtRoomSale delta sync skipped: circuit breaker OPEN");
    } catch (DataAccessException e) {
      log.warn("RealtRoomSale delta sync: DB unavailable, will retry on next schedule: source={}, error={}",
          realtRoomSaleConnector.getSourceId(), e.getMessage());
    } catch (Exception e) {
      log.error("RealtRoomSale delta sync failed: source={}, error={}", realtRoomSaleConnector.getSourceId(), e.getMessage(), e);
      syncRunService.record(SyncRunRequest.failure(
          realtRoomSaleConnector.getSourceId(), SyncType.DELTA, runStart, Instant.now()));
    }
  }

  private boolean fullSyncIsRunning() {
    return realtRoomSaleFullSyncJob != null && realtRoomSaleFullSyncJob.isRunning();
  }

  private void performDeltaSync(Source source, Instant since, Instant runStart) {
    log.info("RealtRoomSale delta sync started: since={}", since);
    List<RawListing> rawListings = realtRoomSaleConnector.fetchDelta(since);

    if (rawListings.isEmpty()) {
      log.info("RealtRoomSale delta sync: no new listings since={}", since);
      Instant finish = Instant.now();
      syncRunService.record(SyncRunRequest.success(
          realtRoomSaleConnector.getSourceId(), SyncType.DELTA, runStart, finish, 0,
          new BatchIngestResult(0, 0, 0)));
      return;
    }

    BatchIngestResult result = listingIngestionService.ingestBatch(rawListings, source);
    Instant finish = Instant.now();
    long durationMs = Duration.between(runStart, finish).toMillis();

    syncRunService.record(SyncRunRequest.success(
        realtRoomSaleConnector.getSourceId(), SyncType.DELTA, runStart, finish,
        rawListings.size(), result));

    log.info("RealtRoomSale delta sync completed: fetched={}, added={}, updated={}, errors={}, durationMs={}",
        rawListings.size(), result.added(), result.updated(), result.errors(), durationMs);
  }

  private void performFullSyncFallback(Source source, Instant runStart) {
    List<RawListing> rawListings = realtRoomSaleConnector.fetch();

    if (rawListings.isEmpty()) {
      log.warn("RealtRoomSale full-sync fallback: fetch returned empty list — skipping to avoid data loss");
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
        realtRoomSaleConnector.getSourceId(), SyncType.FULL, runStart, finish,
        rawListings.size(), result));

    log.info("RealtRoomSale full-sync fallback completed: fetched={}, added={}, updated={}, errors={}, durationMs={}",
        rawListings.size(), result.added(), result.updated(), result.errors(), durationMs);
  }
}
