package com.flatio.integration.realt.scheduler;

import com.flatio.domain.source.Source;
import com.flatio.domain.source.SyncType;
import com.flatio.integration.core.RawListing;
import com.flatio.integration.realt.client.RealtConnector;
import com.flatio.repository.SourceRepository;
import com.flatio.service.ListingIngestionService;
import com.flatio.service.SyncRunService;
import com.flatio.service.domain.BatchIngestResult;
import com.flatio.service.domain.SyncRunRequest;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Performs a full crawl of all Realt.by listings on a daily schedule.
 *
 * <p>After ingesting every active listing, applies the missed-sync penalty to listings that
 * did not appear in the source response — eventually marking them {@code INACTIVE}.
 *
 * <p>On application start, if the database contains no listings for the Realt source,
 * a full sync is triggered immediately without waiting for the cron schedule.
 *
 * <p>Cron schedule is configurable via {@code flatio.sync.realt.full.cron}
 * (env: {@code FLATIO_SYNC_REALT_FULL_CRON}); default is daily at 04:00.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class RealtFullSyncJob {

  private final RealtConnector realtConnector;
  private final SourceRepository sourceRepository;
  private final ListingIngestionService listingIngestionService;
  private final SyncRunService syncRunService;

  /**
   * Triggers an immediate full sync on startup if the database is empty for this source.
   *
   * <p>Fires after the application context is fully initialised so that all beans
   * and database connections are ready.
   */
  @EventListener(ApplicationReadyEvent.class)
  public void onApplicationReady() {
    try {
      Source source = resolveSource();
      long count = listingIngestionService.countBySource(source);
      if (count == 0) {
        log.info("Realt DB is empty on startup — triggering immediate full sync: source={}",
            realtConnector.getSourceId());
        performFullSync(source);
      }
    } catch (Exception e) {
      log.error("Realt startup sync check failed: error={}", e.getMessage(), e);
    }
  }

  /**
   * Runs a full crawl of all Realt.by listings and applies the missed-sync penalty.
   *
   * <p>Deactivation is skipped when {@code fetch} returns an empty list — this prevents
   * accidental mass-deactivation if the source is temporarily unavailable.
   */
  @Scheduled(cron = "${flatio.sync.realt.full.cron}")
  public void runFullSync() {
    log.info("Realt full sync started (scheduled)");
    Instant start = Instant.now();
    try {
      performFullSync(resolveSource());
    } catch (CallNotPermittedException e) {
      log.warn("Realt full sync skipped: circuit breaker OPEN");
    } catch (Exception e) {
      log.error("Realt full sync failed: error={}", e.getMessage(), e);
      syncRunService.record(SyncRunRequest.failure(
          realtConnector.getSourceId(), SyncType.FULL, start, Instant.now()));
    }
  }

  private void performFullSync(Source source) {
    Instant start = Instant.now();
    List<RawListing> rawListings = realtConnector.fetch();

    if (rawListings.isEmpty()) {
      log.warn("Realt full sync: fetch returned empty list — skipping deactivation to avoid data loss");
      return;
    }

    BatchIngestResult result = listingIngestionService.ingestBatch(rawListings, source);

    Set<String> activeExternalIds = rawListings.stream()
        .map(RawListing::externalId)
        .collect(Collectors.toSet());

    int deactivated = listingIngestionService.applyMissedSyncPenalty(source, activeExternalIds);
    Instant finish = Instant.now();
    long durationMs = Duration.between(start, finish).toMillis();

    syncRunService.record(SyncRunRequest.success(
        realtConnector.getSourceId(), SyncType.FULL, start, finish,
        rawListings.size(), result));

    log.info("Realt full sync completed: fetched={}, added={}, updated={}, errors={}, deactivated={}, durationMs={}",
        rawListings.size(), result.added(), result.updated(), result.errors(), deactivated, durationMs);
  }

  private Source resolveSource() {
    return sourceRepository.findByCode(realtConnector.getSourceId())
        .orElseThrow(() -> new IllegalStateException(
            "Source not registered in DB: " + realtConnector.getSourceId()));
  }
}
