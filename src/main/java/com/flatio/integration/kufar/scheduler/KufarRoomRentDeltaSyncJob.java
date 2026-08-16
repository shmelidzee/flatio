package com.flatio.integration.kufar.scheduler;

import com.flatio.domain.source.Source;
import com.flatio.domain.source.SyncType;
import com.flatio.integration.core.RawListing;
import com.flatio.integration.kufar.client.KufarRoomRentConnector;
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
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically fetches only new Kufar room rental listings since the last successful sync.
 *
 * <p>Cron schedule is configurable via {@code flatio.sync.kufar-room-rent.delta.cron}
 * (env: {@code FLATIO_SYNC_KUFAR_ROOM_RENT_DELTA_CRON}); default is every 15 minutes.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class KufarRoomRentDeltaSyncJob {

  private final KufarRoomRentConnector connector;
  private final SourceService sourceService;
  private final ListingIngestionService listingIngestionService;
  private final SyncRunService syncRunService;

  private final KufarRoomRentFullSyncJob fullSyncJob;

  /**
   * Determines sync mode from the last successful run and fetches accordingly.
   *
   * <p>Runs asynchronously on {@code kufarSyncExecutor} (see {@code SchedulerConfig}) so the
   * shared scheduler pool is not blocked by {@code connector-kufar-detail} RateLimiter waits
   * (issue #332).
   */
  @Async("kufarSyncExecutor")
  @Scheduled(cron = "${flatio.sync.kufar-room-rent.delta.cron}")
  public void runDeltaSync() {
    Instant runStart = Instant.now();
    try {
      Source source = sourceService.findByCodeOrThrow(connector.getSourceId());
      if (!source.isActive()) {
        log.debug("KufarRoomRent delta sync skipped: source disabled: source={}", connector.getSourceId());
        return;
      }
      Optional<Instant> lastRunAt = syncRunService.findLastSuccessfulRunAt(connector.getSourceId());
      if (lastRunAt.isPresent()) {
        performDeltaSync(source, lastRunAt.get(), runStart);
      } else if (fullSyncJob.isRunning()) {
        log.info("KufarRoomRent delta sync: FullSyncJob is already running, skipping: source={}", connector.getSourceId());
      } else {
        log.info("KufarRoomRent: no prior successful run — falling back to full sync");
        performFullSyncFallback(source, runStart);
      }
    } catch (CallNotPermittedException e) {
      log.warn("KufarRoomRent delta sync skipped: circuit breaker OPEN");
    } catch (DataAccessException e) {
      log.warn("KufarRoomRent delta sync: DB unavailable: source={}, error={}", connector.getSourceId(), e.getMessage());
    } catch (Exception e) {
      log.error("KufarRoomRent delta sync failed: source={}, error={}", connector.getSourceId(), e.getMessage(), e);
      syncRunService.record(SyncRunRequest.failure(connector.getSourceId(), SyncType.DELTA, runStart, Instant.now()));
    }
  }

  private void performDeltaSync(Source source, Instant since, Instant runStart) {
    log.info("KufarRoomRent delta sync started: since={}", since);
    List<RawListing> rawListings = connector.fetchDelta(since);
    if (rawListings.isEmpty()) {
      log.info("KufarRoomRent delta sync: no new listings since={}", since);
      syncRunService.record(SyncRunRequest.success(connector.getSourceId(), SyncType.DELTA, runStart, Instant.now(), 0, new BatchIngestResult(0, 0, 0)));
      return;
    }
    BatchIngestResult result = listingIngestionService.ingestBatch(rawListings, source);
    Instant finish = Instant.now();
    syncRunService.record(SyncRunRequest.success(connector.getSourceId(), SyncType.DELTA, runStart, finish, rawListings.size(), result));
    log.info("KufarRoomRent delta sync completed: fetched={}, added={}, updated={}, errors={}, durationMs={}",
        rawListings.size(), result.added(), result.updated(), result.errors(), Duration.between(runStart, finish).toMillis());
  }

  private void performFullSyncFallback(Source source, Instant runStart) {
    List<RawListing> rawListings = connector.fetch();
    if (rawListings.isEmpty()) {
      log.warn("KufarRoomRent full-sync fallback: empty list — skipping");
      return;
    }
    BatchIngestResult result = listingIngestionService.ingestBatch(rawListings, source);
    Set<String> activeExternalIds = rawListings.stream().map(RawListing::externalId).collect(Collectors.toSet());
    listingIngestionService.applyMissedSyncPenalty(source, activeExternalIds);
    Instant finish = Instant.now();
    syncRunService.record(SyncRunRequest.success(connector.getSourceId(), SyncType.FULL, runStart, finish, rawListings.size(), result));
    log.info("KufarRoomRent full-sync fallback completed: fetched={}, added={}, updated={}, errors={}, durationMs={}",
        rawListings.size(), result.added(), result.updated(), result.errors(), Duration.between(runStart, finish).toMillis());
  }
}
