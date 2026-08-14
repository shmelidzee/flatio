package com.flatio.integration.realt.scheduler;

import com.flatio.domain.source.Source;
import com.flatio.domain.source.SyncType;
import com.flatio.integration.core.RawListing;
import com.flatio.integration.realt.client.RealtSaleConnector;
import com.flatio.service.ListingIngestionService;
import com.flatio.service.SourceService;
import com.flatio.service.SyncRunService;
import com.flatio.service.domain.BatchIngestResult;
import com.flatio.service.domain.SyncRunRequest;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Performs a full crawl of all Realt.by sale listings on a daily schedule.
 *
 * <p>After ingesting every active listing, applies the missed-sync penalty to listings that
 * did not appear in the source response — eventually marking them {@code INACTIVE}.
 *
 * <p>On application start, if the database contains no listings for the RealtSale source,
 * a full sync is triggered immediately without waiting for the cron schedule.
 *
 * <p>Cron schedule is configurable via {@code flatio.sync.realt-sale.full.cron}
 * (env: {@code FLATIO_SYNC_REALT_SALE_FULL_CRON}); default is daily at 05:00.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class RealtSaleFullSyncJob {

  private final RealtSaleConnector realtSaleConnector;
  private final SourceService sourceService;
  private final ListingIngestionService listingIngestionService;
  private final SyncRunService syncRunService;

  private final AtomicBoolean running = new AtomicBoolean(false);

  /**
   * Returns true while a full sync is actively running.
   *
   * <p>Used by {@link RealtSaleDeltaSyncJob} to skip a redundant full-sync fallback
   * when no prior successful run exists yet (first deploy race condition).
   *
   * @return true if {@link #performFullSync} is currently executing
   */
  public boolean isRunning() {
    return running.get();
  }

  /**
   * Triggers an immediate full sync on startup if the database is empty for this source.
   *
   * <p>Fires after the application context is fully initialised so that all beans
   * and database connections are ready.
   */
  @Async("startupSyncExecutor")
  @EventListener(ApplicationReadyEvent.class)
  public void onApplicationReady() {
    try {
      Source source = resolveSource();
      long count = listingIngestionService.countBySource(source);
      if (count == 0) {
        log.info("RealtSale DB is empty on startup — triggering immediate full sync: source={}",
            realtSaleConnector.getSourceId());
        performFullSync(source);
      }
    } catch (Exception e) {
      log.error("RealtSale startup sync check failed: error={}", e.getMessage(), e);
    }
  }

  /**
   * Runs a full crawl of all Realt.by sale listings and applies the missed-sync penalty.
   *
   * <p>Deactivation is skipped when {@code fetch} returns an empty list — this prevents
   * accidental mass-deactivation if the source is temporarily unavailable.
   */
  @Scheduled(cron = "${flatio.sync.realt-sale.full.cron}")
  public void runFullSync() {
    log.info("RealtSale full sync started (scheduled)");
    Instant start = Instant.now();
    try {
      performFullSync(resolveSource());
    } catch (CallNotPermittedException e) {
      log.warn("RealtSale full sync skipped: circuit breaker OPEN");
    } catch (Exception e) {
      log.error("RealtSale full sync failed: error={}", e.getMessage(), e);
      syncRunService.record(SyncRunRequest.failure(
          realtSaleConnector.getSourceId(), SyncType.FULL, start, Instant.now()));
    }
  }

  private void performFullSync(Source source) {
    if (!source.isActive()) {
      log.debug("RealtSale full sync skipped: source disabled: source={}", realtSaleConnector.getSourceId());
      return;
    }
    running.set(true);
    Instant start = Instant.now();
    try {
      List<RawListing> rawListings = realtSaleConnector.fetch();
      if (rawListings.isEmpty()) {
        log.warn("RealtSale full sync: fetch returned empty list — skipping deactivation to avoid data loss");
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
          realtSaleConnector.getSourceId(), SyncType.FULL, start, finish,
          rawListings.size(), result));
      log.info("RealtSale full sync completed: fetched={}, added={}, updated={}, errors={}, deactivated={}, durationMs={}",
          rawListings.size(), result.added(), result.updated(), result.errors(), deactivated, durationMs);
    } finally {
      running.set(false);
    }
  }

  private Source resolveSource() {
    return sourceService.findByCodeOrThrow(realtSaleConnector.getSourceId());
  }
}
