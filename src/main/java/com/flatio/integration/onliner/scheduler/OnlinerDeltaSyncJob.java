package com.flatio.integration.onliner.scheduler;

import com.flatio.domain.source.Source;
import com.flatio.integration.core.RawListing;
import com.flatio.integration.onliner.client.OnlinerConnector;
import com.flatio.repository.SourceRepository;
import com.flatio.service.ListingIngestionService;
import com.flatio.service.domain.BatchIngestResult;
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
 * Periodically fetches only new and recently updated Onliner listings (delta sync).
 *
 * <p>Tracks the last successful run timestamp and passes it to
 * {@link OnlinerConnector#fetchDelta(Instant)}. On the very first execution
 * (before any successful run) the threshold is {@link Instant#EPOCH}, causing a
 * full page pass — subsequent executions only retrieve listings newer than the previous run.
 *
 * <p>Cron schedule is configurable via {@code flatio.sync.onliner.delta.cron}
 * (env: {@code FLATIO_SYNC_ONLINER_DELTA_CRON}); default is every 10 minutes.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class OnlinerDeltaSyncJob {

  private final OnlinerConnector onlinerConnector;
  private final SourceRepository sourceRepository;
  private final ListingIngestionService listingIngestionService;

  private final AtomicReference<Instant> lastRunAt = new AtomicReference<>(Instant.EPOCH);

  /**
   * Fetches listings newer than the previous run timestamp and persists them.
   *
   * <p>The {@code since} cursor is updated only on a successful fetch to avoid
   * skipping listings after a transient failure.
   */
  @Scheduled(cron = "${flatio.sync.onliner.delta.cron}")
  public void runDeltaSync() {
    Instant since = lastRunAt.get();
    Instant runStart = Instant.now();
    log.info("Onliner delta sync started: since={}", since);

    try {
      Source source = sourceRepository.findByCode(onlinerConnector.getSourceId())
          .orElseThrow(() -> new IllegalStateException(
              "Source not registered in DB: " + onlinerConnector.getSourceId()));

      List<RawListing> rawListings = onlinerConnector.fetchDelta(since);

      if (rawListings.isEmpty()) {
        log.info("Onliner delta sync: no new listings since={}", since);
        lastRunAt.set(runStart);
        return;
      }

      BatchIngestResult result = listingIngestionService.ingestBatch(rawListings, source);
      long durationMs = Duration.between(runStart, Instant.now()).toMillis();

      log.info("Onliner delta sync completed: fetched={}, added={}, updated={}, errors={}, durationMs={}",
          rawListings.size(), result.added(), result.updated(), result.errors(), durationMs);

      lastRunAt.set(runStart);
    } catch (CallNotPermittedException e) {
      log.warn("Onliner delta sync skipped: circuit breaker OPEN");
    } catch (DataAccessException e) {
      log.warn("Onliner delta sync: DB unavailable, will retry on next schedule: source={}, error={}",
          onlinerConnector.getSourceId(), e.getMessage());
    } catch (Exception e) {
      log.error("Onliner delta sync failed: source={}, error={}", onlinerConnector.getSourceId(), e.getMessage(), e);
    }
  }
}
