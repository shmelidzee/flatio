package com.flatio.scheduler;

import com.flatio.repository.ListingRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ActiveListingsMetricsJobTest {

  @Mock
  private ListingRepository listingRepository;

  private SimpleMeterRegistry meterRegistry;
  private ActiveListingsMetricsJob job;

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();
    job = new ActiveListingsMetricsJob(listingRepository, meterRegistry);
  }

  @Test
  void should_register_gauge_per_source_when_initialized() {
    // Given
    var onliner = buildCount("ONLINER", 120);
    var realt = buildCount("REALT", 340);
    when(listingRepository.countActiveGroupedBySource()).thenReturn(List.of(onliner, realt));

    // When
    job.init();

    // Then
    assertThat(gaugeValue("ONLINER")).isEqualTo(120.0);
    assertThat(gaugeValue("REALT")).isEqualTo(340.0);
  }

  @Test
  void should_update_gauge_values_when_refreshed_with_new_counts() {
    // Given — initial state, then the count for ONLINER changes on the next refresh
    var initial = buildCount("ONLINER", 120);
    when(listingRepository.countActiveGroupedBySource()).thenReturn(List.of(initial));
    job.init();
    var updated = buildCount("ONLINER", 150);
    when(listingRepository.countActiveGroupedBySource()).thenReturn(List.of(updated));

    // When
    job.refresh();

    // Then
    assertThat(gaugeValue("ONLINER")).isEqualTo(150.0);
  }

  @Test
  void should_remove_stale_source_row_when_source_has_no_active_listings_anymore() {
    // Given — KUFAR had active listings, then its last one is deactivated
    var kufar = buildCount("KUFAR", 5);
    when(listingRepository.countActiveGroupedBySource()).thenReturn(List.of(kufar));
    job.init();
    when(listingRepository.countActiveGroupedBySource()).thenReturn(List.of());

    // When
    job.refresh();

    // Then — no gauge row registered for KUFAR any more
    assertThat(meterRegistry.find("flatio.listings.active").tags("source", "KUFAR").gauge()).isNull();
  }

  private Double gaugeValue(String source) {
    var gauge = meterRegistry.find("flatio.listings.active").tags("source", source).gauge();
    return gauge == null ? null : gauge.value();
  }

  private ListingRepository.ActiveListingCount buildCount(String sourceCode, long activeCount) {
    var count = mock(ListingRepository.ActiveListingCount.class);
    when(count.getSourceCode()).thenReturn(sourceCode);
    when(count.getActiveCount()).thenReturn(activeCount);
    return count;
  }
}
