package com.flatio.scheduler;

import com.flatio.domain.source.Source;
import com.flatio.integration.core.RawListing;
import com.flatio.integration.onliner.client.OnlinerConnector;
import com.flatio.repository.SourceRepository;
import com.flatio.service.ListingIngestionService;
import com.flatio.service.domain.BatchIngestResult;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OnlinerFullSyncJobTest {

  @Mock private OnlinerConnector onlinerConnector;
  @Mock private SourceRepository sourceRepository;
  @Mock private ListingIngestionService listingIngestionService;

  @InjectMocks
  private OnlinerFullSyncJob fullSyncJob;

  private Source source;

  @BeforeEach
  void setUp() {
    source = buildSource("ONLINER");
    when(onlinerConnector.getSourceId()).thenReturn("ONLINER");
    when(sourceRepository.findByCode("ONLINER")).thenReturn(Optional.of(source));
  }

  // -------------------------------------------------------------------------
  // onApplicationReady — startup checks
  // -------------------------------------------------------------------------

  @Test
  void should_trigger_full_sync_on_startup_when_db_is_empty() {
    // Given — no listings exist for onliner source
    when(listingIngestionService.countBySource(source)).thenReturn(0L);
    when(onlinerConnector.fetchAll()).thenReturn(List.of(buildRawListing("ext-1")));
    when(listingIngestionService.ingestBatch(any(), eq(source)))
        .thenReturn(new BatchIngestResult(1, 0, 0));
    when(listingIngestionService.deactivateMissing(eq(source), any())).thenReturn(0);

    // When
    fullSyncJob.onApplicationReady();

    // Then — full sync was executed
    verify(onlinerConnector).fetchAll();
    verify(listingIngestionService).ingestBatch(any(), eq(source));
  }

  @Test
  void should_not_trigger_full_sync_on_startup_when_db_already_has_listings() {
    // Given — DB already populated
    when(listingIngestionService.countBySource(source)).thenReturn(5L);

    // When
    fullSyncJob.onApplicationReady();

    // Then — no sync triggered
    verify(onlinerConnector, never()).fetchAll();
    verify(listingIngestionService, never()).ingestBatch(any(), any());
  }

  @Test
  void should_not_propagate_exception_when_startup_check_fails() {
    // Given
    when(listingIngestionService.countBySource(source)).thenThrow(new RuntimeException("DB unavailable"));

    // When / Then
    assertThatNoException().isThrownBy(() -> fullSyncJob.onApplicationReady());
  }

  // -------------------------------------------------------------------------
  // runFullSync — happy path
  // -------------------------------------------------------------------------

  @Test
  void should_call_fetchAll_and_ingest_on_scheduled_run() {
    // Given
    var raw = buildRawListing("ext-1");
    when(onlinerConnector.fetchAll()).thenReturn(List.of(raw));
    when(listingIngestionService.ingestBatch(any(), eq(source)))
        .thenReturn(new BatchIngestResult(1, 0, 0));
    when(listingIngestionService.deactivateMissing(eq(source), any())).thenReturn(0);

    // When
    fullSyncJob.runFullSync();

    // Then
    verify(onlinerConnector).fetchAll();
    verify(listingIngestionService).ingestBatch(List.of(raw), source);
  }

  @Test
  void should_deactivate_missing_listings_after_full_sync() {
    // Given
    var raw1 = buildRawListing("ext-1");
    var raw2 = buildRawListing("ext-2");
    when(onlinerConnector.fetchAll()).thenReturn(List.of(raw1, raw2));
    when(listingIngestionService.ingestBatch(any(), eq(source)))
        .thenReturn(new BatchIngestResult(2, 0, 0));

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Set<String>> captor = ArgumentCaptor.forClass(Set.class);
    when(listingIngestionService.deactivateMissing(eq(source), captor.capture())).thenReturn(3);

    // When
    fullSyncJob.runFullSync();

    // Then — deactivation called with exactly the fetched external IDs
    assertThat(captor.getValue()).containsExactlyInAnyOrder("ext-1", "ext-2");
    verify(listingIngestionService).deactivateMissing(eq(source), any());
  }

  @Test
  void should_skip_deactivation_when_fetchAll_returns_empty_list() {
    // Given — empty result indicates a failed fetch, not an empty source
    when(onlinerConnector.fetchAll()).thenReturn(List.of());

    // When
    fullSyncJob.runFullSync();

    // Then — deactivation must NOT be called to guard against mass data loss
    verify(listingIngestionService, never()).deactivateMissing(any(), any());
    verify(listingIngestionService, never()).ingestBatch(any(), any());
  }

  // -------------------------------------------------------------------------
  // runFullSync — error isolation
  // -------------------------------------------------------------------------

  @Test
  void should_not_propagate_exception_when_source_not_found_in_db() {
    // Given
    when(sourceRepository.findByCode("ONLINER")).thenReturn(Optional.empty());

    // When / Then
    assertThatNoException().isThrownBy(() -> fullSyncJob.runFullSync());
    verify(onlinerConnector, never()).fetchAll();
  }

  @Test
  void should_not_propagate_exception_when_fetchAll_throws() {
    // Given
    when(onlinerConnector.fetchAll()).thenThrow(new RuntimeException("Timeout"));

    // When / Then
    assertThatNoException().isThrownBy(() -> fullSyncJob.runFullSync());
    verify(listingIngestionService, never()).ingestBatch(any(), any());
  }

  @Test
  void should_skip_sync_when_circuit_breaker_is_open() {
    // Given
    when(onlinerConnector.fetchAll()).thenThrow(
        CallNotPermittedException.createCallNotPermittedException(CircuitBreaker.ofDefaults("test-cb")));

    // When / Then
    assertThatNoException().isThrownBy(() -> fullSyncJob.runFullSync());
    verify(listingIngestionService, never()).ingestBatch(any(), any());
    verify(listingIngestionService, never()).deactivateMissing(any(), any());
  }

  // -------------------------------------------------------------------------
  // helpers
  // -------------------------------------------------------------------------

  private RawListing buildRawListing(String externalId) {
    return new RawListing(
        externalId, "Test apartment", null, "RENT", "APARTMENT",
        BigDecimal.valueOf(50000), "BYN", null,
        2, null, null, null,
        "Минск", null, null, null,
        "https://onliner.by/" + externalId,
        Instant.parse("2026-06-01T10:00:00Z"), List.of(), null
    );
  }

  private Source buildSource(String code) {
    var src = new Source();
    src.setId(1L);
    src.setCode(code);
    src.setName("Onliner");
    src.setActive(true);
    return src;
  }
}
