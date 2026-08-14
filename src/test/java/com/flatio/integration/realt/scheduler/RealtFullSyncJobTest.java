package com.flatio.integration.realt.scheduler;

import com.flatio.domain.source.Source;
import com.flatio.domain.source.SyncRunStatus;
import com.flatio.domain.source.SyncType;
import com.flatio.integration.core.RawListing;
import com.flatio.integration.realt.client.RealtConnector;
import com.flatio.repository.SourceRepository;
import com.flatio.service.ListingIngestionService;
import com.flatio.service.SyncRunService;
import com.flatio.service.domain.BatchIngestResult;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RealtFullSyncJobTest {

  @Mock private RealtConnector realtConnector;
  @Mock private SourceRepository sourceRepository;
  @Mock private ListingIngestionService listingIngestionService;
  @Mock private SyncRunService syncRunService;

  @InjectMocks
  private RealtFullSyncJob fullSyncJob;

  private Source source;

  @BeforeEach
  void setUp() {
    source = buildSource("REALT");
    when(realtConnector.getSourceId()).thenReturn("REALT");
    when(sourceRepository.findByCode("REALT")).thenReturn(Optional.of(source));
  }

  // -------------------------------------------------------------------------
  // onApplicationReady — startup checks
  // -------------------------------------------------------------------------

  @Test
  void should_trigger_full_sync_on_startup_when_db_is_empty() {
    // Given — no listings exist for realt source
    when(listingIngestionService.countBySource(source)).thenReturn(0L);
    when(realtConnector.fetch()).thenReturn(List.of(buildRawListing("ext-1")));
    when(listingIngestionService.ingestBatch(any(), eq(source)))
        .thenReturn(new BatchIngestResult(1, 0, 0));
    when(listingIngestionService.applyMissedSyncPenalty(eq(source), any())).thenReturn(0);

    // When
    fullSyncJob.onApplicationReady();

    // Then — full sync was executed
    verify(realtConnector).fetch();
    verify(listingIngestionService).ingestBatch(any(), eq(source));
  }

  @Test
  void should_not_trigger_full_sync_on_startup_when_db_already_has_listings() {
    // Given — DB already populated
    when(listingIngestionService.countBySource(source)).thenReturn(5L);

    // When
    fullSyncJob.onApplicationReady();

    // Then — no sync triggered
    verify(realtConnector, never()).fetch();
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
  void should_call_fetch_and_ingest_on_scheduled_run() {
    // Given
    var raw = buildRawListing("ext-1");
    when(realtConnector.fetch()).thenReturn(List.of(raw));
    when(listingIngestionService.ingestBatch(any(), eq(source)))
        .thenReturn(new BatchIngestResult(1, 0, 0));
    when(listingIngestionService.applyMissedSyncPenalty(eq(source), any())).thenReturn(0);

    // When
    fullSyncJob.runFullSync();

    // Then
    verify(realtConnector).fetch();
    verify(listingIngestionService).ingestBatch(List.of(raw), source);
  }

  @Test
  void should_deactivate_missing_listings_after_full_sync() {
    // Given
    var raw1 = buildRawListing("ext-1");
    var raw2 = buildRawListing("ext-2");
    when(realtConnector.fetch()).thenReturn(List.of(raw1, raw2));
    when(listingIngestionService.ingestBatch(any(), eq(source)))
        .thenReturn(new BatchIngestResult(2, 0, 0));

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Set<String>> captor = ArgumentCaptor.forClass(Set.class);
    when(listingIngestionService.applyMissedSyncPenalty(eq(source), captor.capture())).thenReturn(1);

    // When
    fullSyncJob.runFullSync();

    // Then — deactivation called with exactly the fetched external IDs
    assertThat(captor.getValue()).containsExactlyInAnyOrder("ext-1", "ext-2");
    verify(listingIngestionService).applyMissedSyncPenalty(eq(source), any());
  }

  @Test
  void should_skip_deactivation_when_fetch_returns_empty_list() {
    // Given — empty result indicates a failed fetch, not an empty source
    when(realtConnector.fetch()).thenReturn(List.of());

    // When
    fullSyncJob.runFullSync();

    // Then — deactivation must NOT be called to guard against mass data loss
    verify(listingIngestionService, never()).applyMissedSyncPenalty(any(), any());
    verify(listingIngestionService, never()).ingestBatch(any(), any());
  }

  // -------------------------------------------------------------------------
  // runFullSync — error isolation
  // -------------------------------------------------------------------------

  @Test
  void should_not_propagate_exception_when_source_not_found_in_db() {
    // Given
    when(sourceRepository.findByCode("REALT")).thenReturn(Optional.empty());

    // When / Then
    assertThatNoException().isThrownBy(() -> fullSyncJob.runFullSync());
    verify(realtConnector, never()).fetch();
  }

  @Test
  void should_skip_sync_when_source_is_disabled() {
    // Given
    source.setActive(false);

    // When
    fullSyncJob.runFullSync();

    // Then — disabled source is never fetched or recorded
    verify(realtConnector, never()).fetch();
    verify(syncRunService, never()).record(any());
  }

  @Test
  void should_not_propagate_exception_when_fetch_throws() {
    // Given
    when(realtConnector.fetch()).thenThrow(new RuntimeException("Timeout"));

    // When / Then
    assertThatNoException().isThrownBy(() -> fullSyncJob.runFullSync());
    verify(listingIngestionService, never()).ingestBatch(any(), any());
  }

  @Test
  void should_skip_sync_when_circuit_breaker_is_open() {
    // Given
    when(realtConnector.fetch()).thenThrow(
        CallNotPermittedException.createCallNotPermittedException(CircuitBreaker.ofDefaults("test-cb")));

    // When / Then
    assertThatNoException().isThrownBy(() -> fullSyncJob.runFullSync());
    verify(listingIngestionService, never()).ingestBatch(any(), any());
    verify(listingIngestionService, never()).applyMissedSyncPenalty(any(), any());
  }

  // -------------------------------------------------------------------------
  // sync run recording
  // -------------------------------------------------------------------------

  @Test
  void should_record_success_run_when_full_sync_completes() {
    // Given
    var raw = buildRawListing("ext-rec-1");
    when(realtConnector.fetch()).thenReturn(List.of(raw));
    when(listingIngestionService.ingestBatch(any(), eq(source)))
        .thenReturn(new BatchIngestResult(1, 0, 0));
    when(listingIngestionService.applyMissedSyncPenalty(eq(source), any())).thenReturn(0);

    // When
    fullSyncJob.runFullSync();

    // Then — a FULL SUCCESS run is recorded
    verify(syncRunService).record(argThat(r ->
        r.status() == SyncRunStatus.SUCCESS
            && r.syncType() == SyncType.FULL
            && r.listingsFetched() == 1
            && r.listingsAdded() == 1));
  }

  @Test
  void should_record_failure_run_when_fetch_throws() {
    // Given — fetch throws an unexpected exception
    when(realtConnector.fetch()).thenThrow(new RuntimeException("Timeout"));

    // When
    fullSyncJob.runFullSync();

    // Then — a FAILURE run is recorded
    verify(syncRunService).record(argThat(r ->
        r.status() == SyncRunStatus.FAILURE
            && r.syncType() == SyncType.FULL
            && r.listingsFetched() == 0));
  }

  // -------------------------------------------------------------------------
  // isRunning — concurrent sync guard (#241)
  // -------------------------------------------------------------------------

  @Test
  void should_return_false_after_full_sync_completes_successfully() {
    // Given
    when(realtConnector.fetch()).thenReturn(List.of(buildRawListing("ext-flag-1")));
    when(listingIngestionService.ingestBatch(any(), eq(source))).thenReturn(new BatchIngestResult(1, 0, 0));
    when(listingIngestionService.applyMissedSyncPenalty(eq(source), any())).thenReturn(0);

    // When
    fullSyncJob.runFullSync();

    // Then — flag is reset after completion
    assertThat(fullSyncJob.isRunning()).isFalse();
  }

  @Test
  void should_return_false_after_full_sync_fails_with_exception() {
    // Given — fetch throws an unexpected error
    when(realtConnector.fetch()).thenThrow(new RuntimeException("Network error"));

    // When
    fullSyncJob.runFullSync();

    // Then — flag is reset even when sync throws (try/finally guarantee)
    assertThat(fullSyncJob.isRunning()).isFalse();
  }

  // -------------------------------------------------------------------------
  // helpers
  // -------------------------------------------------------------------------

  private RawListing buildRawListing(String externalId) {
    return new RawListing(
        externalId, "Test apartment", null, "RENT", "APARTMENT",
        BigDecimal.valueOf(680), "USD", BigDecimal.valueOf(680), null,
        2, null, null, null,
        "Минск", null, null, null,
        "https://realt.by/rent-flat-for-long/object/" + externalId + "/",
        Instant.parse("2026-06-01T10:00:00Z"), List.of(), true, null, null
    );
  }

  private Source buildSource(String code) {
    var src = new Source();
    src.setId(1L);
    src.setCode(code);
    src.setName("Realt.by");
    src.setActive(true);
    return src;
  }
}
