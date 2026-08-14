package com.flatio.integration.kufar.scheduler;

import com.flatio.domain.source.Source;
import com.flatio.domain.source.SyncType;
import com.flatio.integration.core.RawListing;
import com.flatio.integration.kufar.client.KufarApartmentRentConnector;
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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically fetches only new Kufar apartment rental listings since the last successful sync.
 *
 * <p>Cron schedule is configurable via {@code flatio.sync.kufar-apartment-rent.delta.cron}
 * (env: {@code FLATIO_SYNC_KUFAR_APARTMENT_RENT_DELTA_CRON}); default is every 15 minutes.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class KufarApartmentRentDeltaSyncJob {

  private final KufarApartmentRentConnector connector;
  private final SourceService sourceService;
  private final ListingIngestionService listingIngestionService;
  private final SyncRunService syncRunService;

  private final KufarApartmentRentFullSyncJob fullSyncJob;

  /**
   * Determines sync mode from the last successful run and fetches accordingly.
   */
  @Scheduled(cron = "${flatio.sync.kufar-apartment-rent.delta.cron}")
  public void runDeltaSync() {
    Instant runStart = Instant.now();
    try {
      Source source = sourceService.findByCodeOrThrow(connector.getSourceId());
      if (!source.isActive()) {
        log.debug("KufarApartmentRent delta sync skipped: source disabled: source={}", connector.getSourceId());
        return;
      }
      Optional<Instant> lastRunAt = syncRunService.findLastSuccessfulRunAt(connector.getSourceId());

      if (lastRunAt.isPresent()) {
        performDeltaSync(source, lastRunAt.get(), runStart);
      } else if (fullSyncJob.isRunning()) {
        log.info("KufarApartmentRent delta sync: FullSyncJob is already running, skipping fallback: source={}", connector.getSourceId());
      } else {
        log.info("KufarApartmentRent: no prior successful run found — falling back to full sync");
        performFullSyncFallback(source, runStart);
      }
    } catch (CallNotPermittedException e) {
      log.warn("KufarApartmentRent delta sync skipped: circuit breaker OPEN");
    } catch (DataAccessException e) {
      log.warn("KufarApartmentRent delta sync: DB unavailable, will retry: source={}, error={}", connector.getSourceId(), e.getMessage());
    } catch (Exception e) {
      log.error("KufarApartmentRent delta sync failed: source={}, error={}", connector.getSourceId(), e.getMessage(), e);
      syncRunService.record(SyncRunRequest.failure(connector.getSourceId(), SyncType.DELTA, runStart, Instant.now()));
    }
  }

  private void performDeltaSync(Source source, Instant since, Instant runStart) {
    log.info("KufarApartmentRent delta sync started: since={}", since);
    List<RawListing> rawListings = connector.fetchDelta(since);

    if (rawListings.isEmpty()) {
      log.info("KufarApartmentRent delta sync: no new listings since={}", since);
      syncRunService.record(SyncRunRequest.success(
          connector.getSourceId(), SyncType.DELTA, runStart, Instant.now(), 0, new BatchIngestResult(0, 0, 0)));
      return;
    }

    BatchIngestResult result = listingIngestionService.ingestBatch(rawListings, source);
    Instant finish = Instant.now();
    long durationMs = Duration.between(runStart, finish).toMillis();
    syncRunService.record(SyncRunRequest.success(
        connector.getSourceId(), SyncType.DELTA, runStart, finish, rawListings.size(), result));
    log.info("KufarApartmentRent delta sync completed: fetched={}, added={}, updated={}, errors={}, durationMs={}",
        rawListings.size(), result.added(), result.updated(), result.errors(), durationMs);
  }

  private void performFullSyncFallback(Source source, Instant runStart) {
    List<RawListing> rawListings = connector.fetch();
    if (rawListings.isEmpty()) {
      log.warn("KufarApartmentRent full-sync fallback: fetch returned empty list — skipping to avoid data loss");
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
        connector.getSourceId(), SyncType.FULL, runStart, finish, rawListings.size(), result));
    log.info("KufarApartmentRent full-sync fallback completed: fetched={}, added={}, updated={}, errors={}, durationMs={}",
        rawListings.size(), result.added(), result.updated(), result.errors(), durationMs);
  }
}
