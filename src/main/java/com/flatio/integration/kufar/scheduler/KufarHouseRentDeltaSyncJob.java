package com.flatio.integration.kufar.scheduler;

import com.flatio.domain.source.Source;
import com.flatio.domain.source.SyncType;
import com.flatio.integration.core.RawListing;
import com.flatio.integration.kufar.client.KufarHouseRentConnector;
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
 * Periodically fetches only new Kufar house rental listings since the last successful sync.
 *
 * <p>Cron schedule is configurable via {@code flatio.sync.kufar-house-rent.delta.cron}
 * (env: {@code FLATIO_SYNC_KUFAR_HOUSE_RENT_DELTA_CRON}); default is every 15 minutes.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class KufarHouseRentDeltaSyncJob {

  private final KufarHouseRentConnector connector;
  private final SourceService sourceService;
  private final ListingIngestionService listingIngestionService;
  private final SyncRunService syncRunService;

  @Lazy
  @Autowired
  private KufarHouseRentFullSyncJob fullSyncJob;

  @Scheduled(cron = "${flatio.sync.kufar-house-rent.delta.cron}")
  public void runDeltaSync() {
    Instant runStart = Instant.now();
    try {
      Source source = sourceService.findByCodeOrThrow(connector.getSourceId());
      Optional<Instant> lastRunAt = syncRunService.findLastSuccessfulRunAt(connector.getSourceId());
      if (lastRunAt.isPresent()) {
        performDeltaSync(source, lastRunAt.get(), runStart);
      } else if (fullSyncJob != null && fullSyncJob.isRunning()) {
        log.info("KufarHouseRent delta sync: FullSyncJob is already running, skipping: source={}", connector.getSourceId());
      } else {
        log.info("KufarHouseRent: no prior successful run — falling back to full sync");
        performFullSyncFallback(source, runStart);
      }
    } catch (CallNotPermittedException e) {
      log.warn("KufarHouseRent delta sync skipped: circuit breaker OPEN");
    } catch (DataAccessException e) {
      log.warn("KufarHouseRent delta sync: DB unavailable: source={}, error={}", connector.getSourceId(), e.getMessage());
    } catch (Exception e) {
      log.error("KufarHouseRent delta sync failed: source={}, error={}", connector.getSourceId(), e.getMessage(), e);
      syncRunService.record(SyncRunRequest.failure(connector.getSourceId(), SyncType.DELTA, runStart, Instant.now()));
    }
  }

  private void performDeltaSync(Source source, Instant since, Instant runStart) {
    log.info("KufarHouseRent delta sync started: since={}", since);
    List<RawListing> rawListings = connector.fetchDelta(since);
    if (rawListings.isEmpty()) {
      log.info("KufarHouseRent delta sync: no new listings since={}", since);
      syncRunService.record(SyncRunRequest.success(connector.getSourceId(), SyncType.DELTA, runStart, Instant.now(), 0, new BatchIngestResult(0, 0, 0)));
      return;
    }
    BatchIngestResult result = listingIngestionService.ingestBatch(rawListings, source);
    Instant finish = Instant.now();
    syncRunService.record(SyncRunRequest.success(connector.getSourceId(), SyncType.DELTA, runStart, finish, rawListings.size(), result));
    log.info("KufarHouseRent delta sync completed: fetched={}, added={}, updated={}, errors={}, durationMs={}",
        rawListings.size(), result.added(), result.updated(), result.errors(), Duration.between(runStart, finish).toMillis());
  }

  private void performFullSyncFallback(Source source, Instant runStart) {
    List<RawListing> rawListings = connector.fetch();
    if (rawListings.isEmpty()) {
      log.warn("KufarHouseRent full-sync fallback: empty list — skipping");
      return;
    }
    BatchIngestResult result = listingIngestionService.ingestBatch(rawListings, source);
    Set<String> activeExternalIds = rawListings.stream().map(RawListing::externalId).collect(Collectors.toSet());
    listingIngestionService.applyMissedSyncPenalty(source, activeExternalIds);
    Instant finish = Instant.now();
    syncRunService.record(SyncRunRequest.success(connector.getSourceId(), SyncType.FULL, runStart, finish, rawListings.size(), result));
    log.info("KufarHouseRent full-sync fallback completed: fetched={}, added={}, updated={}, errors={}, durationMs={}",
        rawListings.size(), result.added(), result.updated(), result.errors(), Duration.between(runStart, finish).toMillis());
  }
}
