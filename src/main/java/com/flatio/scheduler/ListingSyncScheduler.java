package com.flatio.scheduler;

import com.flatio.connector.core.ListingConnector;
import com.flatio.connector.core.RawListing;
import com.flatio.domain.source.Source;
import com.flatio.repository.SourceRepository;
import com.flatio.service.domain.BatchIngestResult;
import com.flatio.service.ListingIngestionService;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically syncs listings from all registered connectors.
 *
 * <p>Runs at application startup and then after every fixed delay. Each connector
 * is executed sequentially; an error in one does not abort the others.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ListingSyncScheduler {

  private final List<ListingConnector> connectors;
  private final SourceRepository sourceRepository;
  private final ListingIngestionService listingIngestionService;

  /**
   * Triggers a sync cycle for every registered connector.
   *
   * <p>Delay between cycles is configurable via {@code flatio.sync.interval-ms}
   * (env: {@code FLATIO_SYNC_INTERVAL_MS}); default is 30 minutes.
   */
  @Scheduled(fixedDelayString = "${flatio.sync.interval-ms}", initialDelay = 0)
  public void syncAll() {
    for (ListingConnector connector : connectors) {
      syncConnector(connector);
    }
  }

  private void syncConnector(ListingConnector connector) {
    String sourceId = connector.getSourceId();
    log.info("Sync started: source={}", sourceId);
    Instant start = Instant.now();

    try {
      Source source = sourceRepository.findByCode(sourceId)
          .orElseThrow(() -> new IllegalStateException("Source not registered in DB: " + sourceId));

      List<RawListing> rawListings = connector.fetch();
      int fetchedCount = rawListings.size();

      BatchIngestResult result = listingIngestionService.ingestBatch(rawListings, source);
      long durationMs = Duration.between(start, Instant.now()).toMillis();

      log.info("Sync completed: source={}, fetched={}, added={}, updated={}, errors={}, durationMs={}",
          sourceId, fetchedCount, result.added(), result.updated(), result.errors(), durationMs);
    } catch (CallNotPermittedException e) {
      log.warn("Circuit OPEN, skipping: source={}", sourceId);
    } catch (Exception e) {
      log.error("Sync failed: source={}, error={}", sourceId, e.getMessage(), e);
    }
  }
}
