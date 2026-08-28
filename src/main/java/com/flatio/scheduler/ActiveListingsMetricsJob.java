package com.flatio.scheduler;

import com.flatio.repository.ListingRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import jakarta.annotation.PostConstruct;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically refreshes the "active listings per source" Prometheus gauge (issue #417).
 *
 * <p>Backed by a {@link MultiGauge} rather than one {@code Gauge} per source: the set of sources
 * is not fixed at startup — a new market/connector can register a new source code without any
 * code change here.
 *
 * <p>Refresh interval is configurable via {@code flatio.metrics.active-listings.refresh-rate-ms}
 * (env: {@code FLATIO_METRICS_ACTIVE_LISTINGS_REFRESH_RATE_MS}); default is 60 seconds.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ActiveListingsMetricsJob {

  private final ListingRepository listingRepository;
  private final MeterRegistry meterRegistry;

  private MultiGauge activeListingsGauge;

  /**
   * Registers the gauge and populates it with the current counts, so the metric is already
   * correct before the first scheduled refresh fires.
   */
  @PostConstruct
  void init() {
    activeListingsGauge = MultiGauge.builder("flatio.listings.active")
        .description("Number of ACTIVE listings per source")
        .register(meterRegistry);
    refresh();
  }

  /**
   * Re-reads active listing counts per source from the database and updates the gauge rows.
   */
  @Scheduled(fixedRateString = "${flatio.metrics.active-listings.refresh-rate-ms:60000}")
  public void refresh() {
    List<MultiGauge.Row<?>> rows = listingRepository.countActiveGroupedBySource().stream()
        .<MultiGauge.Row<?>>map(count -> MultiGauge.Row.of(Tags.of("source", count.getSourceCode()), count.getActiveCount()))
        .toList();
    activeListingsGauge.register(rows, true);
    log.debug("Active listings gauge refreshed: sources={}", rows.size());
  }
}
