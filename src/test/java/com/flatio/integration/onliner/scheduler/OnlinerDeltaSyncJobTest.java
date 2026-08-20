package com.flatio.integration.onliner.scheduler;

import com.flatio.domain.source.Source;
import com.flatio.integration.core.RawListing;
import com.flatio.integration.onliner.client.OnlinerConnector;
import com.flatio.repository.SourceRepository;
import com.flatio.service.ListingIngestionService;
import com.flatio.service.SyncRunService;
import com.flatio.service.domain.BatchIngestResult;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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
class OnlinerDeltaSyncJobTest {

  private static final String SOURCE_ID = "ONLINER";

  @Mock private OnlinerConnector onlinerConnector;
  @Mock private SourceRepository sourceRepository;
  @Mock private ListingIngestionService listingIngestionService;
  @Mock private SyncRunService syncRunService;

  @InjectMocks
  private OnlinerDeltaSyncJob deltaSyncJob;

  private Source source;

  @BeforeEach
  void setUp() {
    source = buildSource(SOURCE_ID);
    when(onlinerConnector.getSourceId()).thenReturn(SOURCE_ID);
    when(sourceRepository.findByCode(SOURCE_ID)).thenReturn(Optional.of(source));
  }

  // -------------------------------------------------------------------------
  // cursor — read from SyncRunService, not kept in memory (issue #369)
  // -------------------------------------------------------------------------

  @Test
  void should_call_fetchDelta_with_epoch_when_no_prior_successful_run_recorded() {
    // Given
    when(syncRunService.findLastSuccessfulRunAt(SOURCE_ID)).thenReturn(Optional.empty());
    var raw = buildRawListing("ext-1");
    when(onlinerConnector.fetchDelta(Instant.EPOCH)).thenReturn(List.of(raw));
    when(listingIngestionService.ingestBatch(any(), eq(source)))
        .thenReturn(new BatchIngestResult(1, 0, 0));

    // When
    deltaSyncJob.runDeltaSync();

    // Then — delta was called with EPOCH as lower bound
    verify(onlinerConnector).fetchDelta(Instant.EPOCH);
    verify(listingIngestionService).ingestBatch(List.of(raw), source);
  }

  @Test
  void should_call_fetchDelta_with_db_cursor_when_prior_successful_run_recorded() {
    // Given — the cursor comes from SyncRunService, surviving a hypothetical restart
    Instant lastRun = Instant.parse("2026-06-27T04:00:00Z");
    when(syncRunService.findLastSuccessfulRunAt(SOURCE_ID)).thenReturn(Optional.of(lastRun));
    when(onlinerConnector.fetchDelta(lastRun)).thenReturn(List.of(buildRawListing("ext-1")));
    when(listingIngestionService.ingestBatch(any(), eq(source)))
        .thenReturn(new BatchIngestResult(1, 0, 0));

    // When
    deltaSyncJob.runDeltaSync();

    // Then
    verify(onlinerConnector).fetchDelta(lastRun);
  }

  @Test
  void should_pass_fetched_listings_to_ingest_batch() {
    // Given
    when(syncRunService.findLastSuccessfulRunAt(SOURCE_ID)).thenReturn(Optional.empty());
    var raw1 = buildRawListing("ext-1");
    var raw2 = buildRawListing("ext-2");
    when(onlinerConnector.fetchDelta(any())).thenReturn(List.of(raw1, raw2));
    when(listingIngestionService.ingestBatch(any(), eq(source)))
        .thenReturn(new BatchIngestResult(2, 0, 0));

    // When
    deltaSyncJob.runDeltaSync();

    // Then
    verify(listingIngestionService).ingestBatch(List.of(raw1, raw2), source);
  }

  @Test
  void should_skip_ingest_when_fetch_returns_empty_list() {
    // Given
    when(syncRunService.findLastSuccessfulRunAt(SOURCE_ID)).thenReturn(Optional.empty());
    when(onlinerConnector.fetchDelta(any())).thenReturn(List.of());

    // When
    deltaSyncJob.runDeltaSync();

    // Then — no ingest called for an empty delta
    verify(listingIngestionService, never()).ingestBatch(any(), any());
  }

  // -------------------------------------------------------------------------
  // error isolation
  // -------------------------------------------------------------------------

  @Test
  void should_not_propagate_exception_when_source_not_found_in_db() {
    // Given
    when(sourceRepository.findByCode(SOURCE_ID)).thenReturn(Optional.empty());

    // When / Then — no exception propagates
    assertThatNoException().isThrownBy(() -> deltaSyncJob.runDeltaSync());
    verify(onlinerConnector, never()).fetchDelta(any());
  }

  @Test
  void should_skip_sync_when_source_is_disabled() {
    // Given
    source.setActive(false);

    // When
    deltaSyncJob.runDeltaSync();

    // Then — disabled source is never fetched or recorded
    verify(onlinerConnector, never()).fetchDelta(any());
    verify(syncRunService, never()).record(any());
  }

  @Test
  void should_not_propagate_exception_when_fetch_throws() {
    // Given
    when(syncRunService.findLastSuccessfulRunAt(SOURCE_ID)).thenReturn(Optional.empty());
    when(onlinerConnector.fetchDelta(any())).thenThrow(new RuntimeException("Connection refused"));

    // When / Then
    assertThatNoException().isThrownBy(() -> deltaSyncJob.runDeltaSync());
    verify(listingIngestionService, never()).ingestBatch(any(), any());
  }

  @Test
  void should_skip_sync_when_circuit_breaker_is_open() {
    // Given
    when(syncRunService.findLastSuccessfulRunAt(SOURCE_ID)).thenReturn(Optional.empty());
    when(onlinerConnector.fetchDelta(any())).thenThrow(
        CallNotPermittedException.createCallNotPermittedException(CircuitBreaker.ofDefaults("test-cb")));

    // When / Then — no exception propagates, ingest not called
    assertThatNoException().isThrownBy(() -> deltaSyncJob.runDeltaSync());
    verify(listingIngestionService, never()).ingestBatch(any(), any());
  }

  @Test
  void should_not_propagate_exception_when_db_unavailable_during_ingest() {
    // Given — ingestBatch throws DataAccessException (DB down)
    when(syncRunService.findLastSuccessfulRunAt(SOURCE_ID)).thenReturn(Optional.empty());
    var raw = buildRawListing("ext-db-err");
    when(onlinerConnector.fetchDelta(any())).thenReturn(List.of(raw));
    when(listingIngestionService.ingestBatch(any(), eq(source)))
        .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("DB connection lost"));

    // When / Then — DataAccessException must not propagate; job absorbs it gracefully
    assertThatNoException().isThrownBy(() -> deltaSyncJob.runDeltaSync());
  }

  // -------------------------------------------------------------------------
  // sync run recording
  // -------------------------------------------------------------------------

  @Test
  void should_record_success_run_when_delta_sync_completes_with_listings() {
    // Given
    when(syncRunService.findLastSuccessfulRunAt(SOURCE_ID)).thenReturn(Optional.empty());
    var raw = buildRawListing("ext-rec-1");
    when(onlinerConnector.fetchDelta(any())).thenReturn(List.of(raw));
    when(listingIngestionService.ingestBatch(any(), eq(source)))
        .thenReturn(new BatchIngestResult(1, 0, 0));

    // When
    deltaSyncJob.runDeltaSync();

    // Then — a SUCCESS run is recorded
    ArgumentCaptor<com.flatio.service.domain.SyncRunRequest> captor =
        ArgumentCaptor.forClass(com.flatio.service.domain.SyncRunRequest.class);
    verify(syncRunService).record(captor.capture());
    assertThat(captor.getValue().status()).isEqualTo(com.flatio.domain.source.SyncRunStatus.SUCCESS);
    assertThat(captor.getValue().syncType()).isEqualTo(com.flatio.domain.source.SyncType.DELTA);
    assertThat(captor.getValue().listingsFetched()).isEqualTo(1);
  }

  @Test
  void should_record_success_run_when_delta_returns_empty_list() {
    // Given — empty response is still a successful run (no new listings)
    when(syncRunService.findLastSuccessfulRunAt(SOURCE_ID)).thenReturn(Optional.empty());
    when(onlinerConnector.fetchDelta(any())).thenReturn(List.of());

    // When
    deltaSyncJob.runDeltaSync();

    // Then — run is still recorded as SUCCESS
    ArgumentCaptor<com.flatio.service.domain.SyncRunRequest> captor =
        ArgumentCaptor.forClass(com.flatio.service.domain.SyncRunRequest.class);
    verify(syncRunService).record(captor.capture());
    assertThat(captor.getValue().status()).isEqualTo(com.flatio.domain.source.SyncRunStatus.SUCCESS);
    assertThat(captor.getValue().listingsFetched()).isZero();
  }

  @Test
  void should_record_failure_run_when_fetch_throws() {
    // Given — fetchDelta throws an unexpected exception
    when(syncRunService.findLastSuccessfulRunAt(SOURCE_ID)).thenReturn(Optional.empty());
    when(onlinerConnector.fetchDelta(any())).thenThrow(new RuntimeException("Connection refused"));

    // When
    deltaSyncJob.runDeltaSync();

    // Then — a FAILURE run is recorded so the health indicator can detect the gap
    ArgumentCaptor<com.flatio.service.domain.SyncRunRequest> captor =
        ArgumentCaptor.forClass(com.flatio.service.domain.SyncRunRequest.class);
    verify(syncRunService).record(captor.capture());
    assertThat(captor.getValue().status()).isEqualTo(com.flatio.domain.source.SyncRunStatus.FAILURE);
    assertThat(captor.getValue().syncType()).isEqualTo(com.flatio.domain.source.SyncType.DELTA);
    assertThat(captor.getValue().listingsFetched()).isZero();
  }

  // -------------------------------------------------------------------------
  // helpers
  // -------------------------------------------------------------------------

  private RawListing buildRawListing(String externalId) {
    return new RawListing(
        externalId, "Test apartment", null, "RENT", "APARTMENT",
        BigDecimal.valueOf(50000), "BYN", null, null,
        2, null, null, null,
        "Минск", null, null, null,
        "https://onliner.by/" + externalId,
        Instant.parse("2026-06-01T10:00:00Z"), List.of(), null, null, null
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
