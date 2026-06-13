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
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically fetches only new and recently updated Onliner sale listings (delta sync).
 *
 * <p>Tracks the last successful run timestamp and passes it to
 * {@link OnlinerSaleConnector#fetchDelta(Instant)}. On the very first execution
 * (before any successful run) the threshold is {@link Instant#EPOCH}, causing a
 * full page pass — subsequent executions only retrieve listings newer than the previous run.
 *
 * <p>Cron schedule is configurable via {@code flatio.sync.onliner-sale.delta.cron}
 * (env: {@code FLATIO_SYNC_ONLINER_SALE_DELTA_CRON}); default is every 15 minutes.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class OnlinerSaleDeltaSyncJob {

  private final OnlinerSaleConnector onlinerSaleConnector;
  private final SourceRepository sourceRepository;
  private final ListingIngestionService listingIngestionService;
  private final SyncRunService syncRunService;

  private final AtomicReference<Instant> lastRunAt = new AtomicReference<>(Instant.EPOCH);

  /**
   * Fetches sale listings newer than the previous run timestamp and persists them.
   *
   * <p>The {@code since} cursor is updated only on a successful fetch to avoid
   * skipping listings after a transient failure.
   */
  @Scheduled(cron = "${flatio.sync.onliner-sale.delta.cron}")
  public void runDeltaSync() {
    Instant since = lastRunAt.get();
    Instant runStart = Instant.now();
    log.info("Onliner sale delta sync started: since={}", since);

    try {
      Source source = sourceRepository.findByCode(onlinerSaleConnector.getSourceId())
          .orElseThrow(() -> new IllegalStateException(
              "Source not registered in DB: " + onlinerSaleConnector.getSourceId()));

      List<RawListing> rawListings = onlinerSaleConnector.fetchDelta(since);

      if (rawListings.isEmpty()) {
        log.info("Onliner sale delta sync: no new listings since={}", since);
        lastRunAt.set(runStart);
        Instant finish = Instant.now();
        syncRunService.record(SyncRunRequest.success(
            onlinerSaleConnector.getSourceId(), SyncType.DELTA, runStart, finish, 0,
            new BatchIngestResult(0, 0, 0)));
        return;
      }

      BatchIngestResult result = listingIngestionService.ingestBatch(rawListings, source);
      Instant finish = Instant.now();
      long durationMs = Duration.between(runStart, finish).toMillis();

      syncRunService.record(SyncRunRequest.success(
          onlinerSaleConnector.getSourceId(), SyncType.DELTA, runStart, finish,
          rawListings.size(), result));

      log.info("Onliner sale delta sync completed: fetched={}, added={}, updated={}, errors={}, durationMs={}",
          rawListings.size(), result.added(), result.updated(), result.errors(), durationMs);

      lastRunAt.set(runStart);
    } catch (CallNotPermittedException e) {
      log.warn("Onliner sale delta sync skipped: circuit breaker OPEN");
    } catch (DataAccessException e) {
      log.warn("Onliner sale delta sync: DB unavailable, will retry on next schedule: source={}, error={}",
          onlinerSaleConnector.getSourceId(), e.getMessage());
    } catch (Exception e) {
      log.error("Onliner sale delta sync failed: source={}, error={}", onlinerSaleConnector.getSourceId(), e.getMessage(), e);
      syncRunService.record(SyncRunRequest.failure(
          onlinerSaleConnector.getSourceId(), SyncType.DELTA, runStart, Instant.now()));
    }
  }
}
