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
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Performs a full crawl of all Onliner sale listings on a daily schedule.
 *
 * <p>After ingesting every active listing, marks any listing that did not appear
 * in the API response as {@code INACTIVE} — this detects listings removed by their owners.
 *
 * <p>On application start, if the database contains no listings for the Onliner sale source,
 * a full sync is triggered immediately without waiting for the cron schedule.
 *
 * <p>Cron schedule is configurable via {@code flatio.sync.onliner-sale.full.cron}
 * (env: {@code FLATIO_SYNC_ONLINER_SALE_FULL_CRON}); default is daily at 03:00.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class OnlinerSaleFullSyncJob {

  private final OnlinerSaleConnector onlinerSaleConnector;
  private final SourceRepository sourceRepository;
  private final ListingIngestionService listingIngestionService;
  private final SyncRunService syncRunService;

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
        log.info("Onliner sale DB is empty on startup — triggering immediate full sync: source={}",
            onlinerSaleConnector.getSourceId());
        performFullSync(source);
      }
    } catch (Exception e) {
      log.error("Onliner sale startup sync check failed: error={}", e.getMessage(), e);
    }
  }

  /**
   * Runs a full crawl of all Onliner sale listings and deactivates those that have disappeared.
   *
   * <p>Deactivation is skipped when {@code fetchAll} returns an empty list — this prevents
   * accidental mass-deactivation if the API is temporarily unavailable.
   */
  @Scheduled(cron = "${flatio.sync.onliner-sale.full.cron}")
  public void runFullSync() {
    log.info("Onliner sale full sync started (scheduled)");
    Instant start = Instant.now();
    try {
      performFullSync(resolveSource());
    } catch (CallNotPermittedException e) {
      log.warn("Onliner sale full sync skipped: circuit breaker OPEN");
    } catch (Exception e) {
      log.error("Onliner sale full sync failed: error={}", e.getMessage(), e);
      syncRunService.record(SyncRunRequest.failure(
          onlinerSaleConnector.getSourceId(), SyncType.FULL, start, Instant.now()));
    }
  }

  private void performFullSync(Source source) {
    Instant start = Instant.now();
    List<RawListing> rawListings = onlinerSaleConnector.fetchAll();

    if (rawListings.isEmpty()) {
      log.warn("Onliner sale full sync: fetchAll returned empty list — skipping deactivation to avoid data loss");
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
        onlinerSaleConnector.getSourceId(), SyncType.FULL, start, finish,
        rawListings.size(), result));

    log.info("Onliner sale full sync completed: fetched={}, added={}, updated={}, errors={}, deactivated={}, durationMs={}",
        rawListings.size(), result.added(), result.updated(), result.errors(), deactivated, durationMs);
  }

  private Source resolveSource() {
    return sourceRepository.findByCode(onlinerSaleConnector.getSourceId())
        .orElseThrow(() -> new IllegalStateException(
            "Source not registered in DB: " + onlinerSaleConnector.getSourceId()));
  }
}
