package com.flatio.integration.onliner.scheduler;

import com.flatio.domain.source.Source;
import com.flatio.integration.core.RawListing;
import com.flatio.integration.onliner.client.OnlinerConnector;
import com.flatio.repository.SourceRepository;
import com.flatio.service.ListingIngestionService;
import com.flatio.service.SyncRunService;
import com.flatio.service.domain.BatchIngestResult;
import com.flatio.service.domain.SyncRunRequest;
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
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OnlinerDeltaSyncJobTest {

  @Mock private OnlinerConnector onlinerConnector;
  @Mock private SourceRepository sourceRepository;
  @Mock private ListingIngestionService listingIngestionService;
  @Mock private SyncRunService syncRunService;

  @InjectMocks
  private OnlinerDeltaSyncJob deltaSyncJob;

  private Source source;

  @BeforeEach
  void setUp() {
    source = buildSource("ONLINER");
    when(onlinerConnector.getSourceId()).thenReturn("ONLINER");
    when(sourceRepository.findByCode("ONLINER")).thenReturn(Optional.of(source));
  }

  // -------------------------------------------------------------------------
  // happy path
  // -------------------------------------------------------------------------

  @Test
  void should_call_fetchDelta_with_epoch_on_first_run() {
    // Given — first run, lastRunAt defaults to EPOCH
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
  void should_pass_fetched_listings_to_ingest_batch() {
    // Given
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
  void should_update_cursor_after_successful_run_with_listings() {
    // Given — capture the timestamp used in the second call
    var raw = buildRawListing("ext-1");
    when(onlinerConnector.fetchDelta(any())).thenReturn(List.of(raw));
    when(listingIngestionService.ingestBatch(any(), eq(source)))
        .thenReturn(new BatchIngestResult(1, 0, 0));

    Instant before = Instant.now();
    deltaSyncJob.runDeltaSync();
    Instant after = Instant.now();

    // When — second run; cursor should be > EPOCH
    var captor = ArgumentCaptor.forClass(Instant.class);
    when(onlinerConnector.fetchDelta(captor.capture())).thenReturn(List.of());
    deltaSyncJob.runDeltaSync();

    // Then — cursor advanced past EPOCH
    assertThat(captor.getValue()).isAfterOrEqualTo(before).isBeforeOrEqualTo(after);
  }

  @Test
  void should_update_cursor_after_run_that_returned_no_new_listings() {
    // Given — empty result still advances the cursor
    when(onlinerConnector.fetchDelta(Instant.EPOCH)).thenReturn(List.of());

    deltaSyncJob.runDeltaSync();

    // When — second run; cursor should no longer be EPOCH
    var captor = ArgumentCaptor.forClass(Instant.class);
    when(onlinerConnector.fetchDelta(captor.capture())).thenReturn(List.of());
    deltaSyncJob.runDeltaSync();

    // Then
    assertThat(captor.getValue()).isAfter(Instant.EPOCH);
  }

  @Test
  void should_skip_ingest_when_fetch_returns_empty_list() {
    // Given
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
    when(sourceRepository.findByCode("ONLINER")).thenReturn(Optional.empty());

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
    when(onlinerConnector.fetchDelta(any())).thenThrow(new RuntimeException("Connection refused"));

    // When / Then
    assertThatNoException().isThrownBy(() -> deltaSyncJob.runDeltaSync());
    verify(listingIngestionService, never()).ingestBatch(any(), any());
  }

  @Test
  void should_skip_sync_when_circuit_breaker_is_open() {
    // Given
    when(onlinerConnector.fetchDelta(any())).thenThrow(
        CallNotPermittedException.createCallNotPermittedException(CircuitBreaker.ofDefaults("test-cb")));

    // When / Then — no exception propagates, ingest not called
    assertThatNoException().isThrownBy(() -> deltaSyncJob.runDeltaSync());
    verify(listingIngestionService, never()).ingestBatch(any(), any());
  }

  @Test
  void should_not_propagate_exception_when_db_unavailable_during_ingest() {
    // Given — ingestBatch throws DataAccessException (DB down)
    var raw = buildRawListing("ext-db-err");
    when(onlinerConnector.fetchDelta(any())).thenReturn(List.of(raw));
    when(listingIngestionService.ingestBatch(any(), eq(source)))
        .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("DB connection lost"));

    // When / Then — DataAccessException must not propagate; job absorbs it gracefully
    assertThatNoException().isThrownBy(() -> deltaSyncJob.runDeltaSync());
  }

  @Test
  void should_not_update_cursor_when_fetch_throws() {
    // Given — first call throws, second call returns empty; both must receive EPOCH
    doThrow(new RuntimeException("Timeout"))
        .doReturn(List.of())
        .when(onlinerConnector).fetchDelta(Instant.EPOCH);

    // When — two runs
    deltaSyncJob.runDeltaSync();
    deltaSyncJob.runDeltaSync();

    // Then — cursor was not advanced: both calls received EPOCH
    verify(onlinerConnector, times(2)).fetchDelta(Instant.EPOCH);
  }

  // -------------------------------------------------------------------------
  // sync run recording
  // -------------------------------------------------------------------------

  @Test
  void should_record_success_run_when_delta_sync_completes_with_listings() {
    // Given
    var raw = buildRawListing("ext-rec-1");
    when(onlinerConnector.fetchDelta(any())).thenReturn(List.of(raw));
    when(listingIngestionService.ingestBatch(any(), eq(source)))
        .thenReturn(new BatchIngestResult(1, 0, 0));

    // When
    deltaSyncJob.runDeltaSync();

    // Then — a SUCCESS run is recorded
    verify(syncRunService).record(argThat(r ->
        r.status() == com.flatio.domain.source.SyncRunStatus.SUCCESS
            && r.syncType() == com.flatio.domain.source.SyncType.DELTA
            && r.listingsFetched() == 1));
  }

  @Test
  void should_record_success_run_when_delta_returns_empty_list() {
    // Given — empty response is still a successful run (no new listings)
    when(onlinerConnector.fetchDelta(any())).thenReturn(List.of());

    // When
    deltaSyncJob.runDeltaSync();

    // Then — run is still recorded as SUCCESS
    verify(syncRunService).record(argThat(r ->
        r.status() == com.flatio.domain.source.SyncRunStatus.SUCCESS
            && r.listingsFetched() == 0));
  }

  @Test
  void should_record_failure_run_when_fetch_throws() {
    // Given — fetchDelta throws an unexpected exception
    when(onlinerConnector.fetchDelta(any())).thenThrow(new RuntimeException("Connection refused"));

    // When
    deltaSyncJob.runDeltaSync();

    // Then — a FAILURE run is recorded so the health indicator can detect the gap
    verify(syncRunService).record(argThat(r ->
        r.status() == com.flatio.domain.source.SyncRunStatus.FAILURE
            && r.syncType() == com.flatio.domain.source.SyncType.DELTA
            && r.listingsFetched() == 0));
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
