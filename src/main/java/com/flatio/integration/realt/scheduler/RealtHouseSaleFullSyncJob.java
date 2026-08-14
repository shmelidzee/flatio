package com.flatio.integration.realt.scheduler;

import com.flatio.domain.source.Source;
import com.flatio.domain.source.SyncType;
import com.flatio.integration.core.RawListing;
import com.flatio.integration.realt.client.RealtHouseSaleConnector;
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
 * Performs a full crawl of all Realt.by house sale listings on a daily schedule.
 *
 * <p>After ingesting every active listing, applies the missed-sync penalty to listings that
 * did not appear in the source response — eventually marking them {@code INACTIVE}.
 *
 * <p>On application start, if the database contains no listings for the RealtHouseSale source,
 * a full sync is triggered immediately without waiting for the cron schedule.
 *
 * <p>Cron schedule is configurable via {@code flatio.sync.realt-house-sale.full.cron}
 * (env: {@code FLATIO_SYNC_REALT_HOUSE_SALE_FULL_CRON}); default is daily at 08:00.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class RealtHouseSaleFullSyncJob {

  private final RealtHouseSaleConnector realtHouseSaleConnector;
  private final SourceService sourceService;
  private final ListingIngestionService listingIngestionService;
  private final SyncRunService syncRunService;

  private final AtomicBoolean running = new AtomicBoolean(false);

  /**
   * Returns true while a full sync is actively running.
   *
   * @return true if {@link #performFullSync} is currently executing
   */
  public boolean isRunning() {
    return running.get();
  }

  /**
   * Triggers an immediate full sync on startup if the database is empty for this source.
   */
  @Async("startupSyncExecutor")
  @EventListener(ApplicationReadyEvent.class)
  public void onApplicationReady() {
    try {
      Source source = resolveSource();
      long count = listingIngestionService.countBySource(source);
      if (count == 0) {
        log.info("RealtHouseSale DB is empty on startup — triggering immediate full sync: source={}",
            realtHouseSaleConnector.getSourceId());
        performFullSync(source);
      }
    } catch (Exception e) {
      log.error("RealtHouseSale startup sync check failed: error={}", e.getMessage(), e);
    }
  }

  /**
   * Runs a full crawl of all Realt.by house sale listings and applies the missed-sync penalty.
   */
  @Scheduled(cron = "${flatio.sync.realt-house-sale.full.cron}")
  public void runFullSync() {
    log.info("RealtHouseSale full sync started (scheduled)");
    Instant start = Instant.now();
    try {
      performFullSync(resolveSource());
    } catch (CallNotPermittedException e) {
      log.warn("RealtHouseSale full sync skipped: circuit breaker OPEN");
    } catch (Exception e) {
      log.error("RealtHouseSale full sync failed: error={}", e.getMessage(), e);
      syncRunService.record(SyncRunRequest.failure(
          realtHouseSaleConnector.getSourceId(), SyncType.FULL, start, Instant.now()));
    }
  }

  private void performFullSync(Source source) {
    if (!source.isActive()) {
      log.debug("RealtHouseSale full sync skipped: source disabled: source={}", realtHouseSaleConnector.getSourceId());
      return;
    }
    running.set(true);
    Instant start = Instant.now();
    try {
      List<RawListing> rawListings = realtHouseSaleConnector.fetch();
      if (rawListings.isEmpty()) {
        log.warn("RealtHouseSale full sync: fetch returned empty list — skipping deactivation to avoid data loss");
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
          realtHouseSaleConnector.getSourceId(), SyncType.FULL, start, finish,
          rawListings.size(), result));
      log.info("RealtHouseSale full sync completed: fetched={}, added={}, updated={}, errors={}, deactivated={}, durationMs={}",
          rawListings.size(), result.added(), result.updated(), result.errors(), deactivated, durationMs);
    } finally {
      running.set(false);
    }
  }

  private Source resolveSource() {
    return sourceService.findByCodeOrThrow(realtHouseSaleConnector.getSourceId());
  }
}
