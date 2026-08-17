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
 * Performs a full crawl of all Kufar room rental listings on a daily schedule.
 *
 * <p>Cron schedule is configurable via {@code flatio.sync.kufar-room-rent.full.cron}
 * (env: {@code FLATIO_SYNC_KUFAR_ROOM_RENT_FULL_CRON}); default is daily at 04:30.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class KufarRoomRentFullSyncJob {

  private final KufarRoomRentConnector connector;
  private final SourceService sourceService;
  private final ListingIngestionService listingIngestionService;
  private final SyncRunService syncRunService;

  private final AtomicBoolean running = new AtomicBoolean(false);

  /** Returns true while a full sync is actively running. */
  public boolean isRunning() {
    return running.get();
  }

  @Async("startupSyncExecutor")
  @EventListener(ApplicationReadyEvent.class)
  public void onApplicationReady() {
    try {
      Source source = resolveSource();
      if (listingIngestionService.countBySource(source) == 0) {
        log.info("KufarRoomRent DB is empty on startup — triggering immediate full sync: source={}", connector.getSourceId());
        performFullSync(source);
      }
    } catch (Exception e) {
      log.error("KufarRoomRent startup sync check failed: error={}", e.getMessage(), e);
    }
  }

  /**
   * Runs asynchronously on {@code kufarSyncExecutor} (see {@code SchedulerConfig}) so the
   * shared scheduler pool is not blocked by {@code connector-kufar-detail} RateLimiter waits
   * (issue #332).
   */
  @Async("kufarSyncExecutor")
  @Scheduled(cron = "${flatio.sync.kufar-room-rent.full.cron}")
  public void runFullSync() {
    log.info("KufarRoomRent full sync started (scheduled)");
    Instant start = Instant.now();
    try {
      performFullSync(resolveSource());
    } catch (CallNotPermittedException e) {
      log.warn("KufarRoomRent full sync skipped: circuit breaker OPEN");
    } catch (Exception e) {
      log.error("KufarRoomRent full sync failed: error={}", e.getMessage(), e);
      syncRunService.record(SyncRunRequest.failure(connector.getSourceId(), SyncType.FULL, start, Instant.now()));
    }
  }

  private void performFullSync(Source source) {
    if (!source.isActive()) {
      log.debug("KufarRoomRent full sync skipped: source disabled: source={}", connector.getSourceId());
      return;
    }
    running.set(true);
    Instant start = Instant.now();
    try {
      List<RawListing> rawListings = connector.fetch();
      if (rawListings.isEmpty()) {
        log.warn("KufarRoomRent full sync: fetch returned empty list — skipping deactivation to avoid data loss");
        return;
      }
      BatchIngestResult result = listingIngestionService.ingestBatch(rawListings, source);
      Set<String> activeExternalIds = rawListings.stream().map(RawListing::externalId).collect(Collectors.toSet());
      int deactivated = listingIngestionService.applyMissedSyncPenalty(source, activeExternalIds);
      Instant finish = Instant.now();
      syncRunService.record(SyncRunRequest.success(connector.getSourceId(), SyncType.FULL, start, finish, rawListings.size(), result));
      log.info("KufarRoomRent full sync completed: fetched={}, added={}, updated={}, errors={}, deactivated={}, durationMs={}",
          rawListings.size(), result.added(), result.updated(), result.errors(), deactivated, Duration.between(start, finish).toMillis());
    } finally {
      running.set(false);
    }
  }

  private Source resolveSource() {
    return sourceService.findByCodeOrThrow(connector.getSourceId());
  }
}
