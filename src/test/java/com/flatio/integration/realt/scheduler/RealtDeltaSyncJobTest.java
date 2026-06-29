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
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RealtDeltaSyncJobTest {

  private static final String SOURCE_ID = "REALT";

  @Mock
  private RealtConnector realtConnector;

  @Mock
  private SourceRepository sourceRepository;

  @Mock
  private ListingIngestionService listingIngestionService;

  @Mock
  private SyncRunService syncRunService;

  @Mock
  private RealtFullSyncJob realtFullSyncJob;

  @InjectMocks
  private RealtDeltaSyncJob job;

  @BeforeEach
  void setUp() {
    when(realtConnector.getSourceId()).thenReturn(SOURCE_ID);
    when(sourceRepository.findByCode(SOURCE_ID)).thenReturn(Optional.of(new Source()));
    // @Lazy @Autowired field is not set by @InjectMocks constructor injection; inject explicitly
    ReflectionTestUtils.setField(job, "realtFullSyncJob", realtFullSyncJob);
  }

  // -------------------------------------------------------------------------
  // Delta mode — last successful run found in DB
  // -------------------------------------------------------------------------

  @Test
  void should_run_delta_sync_when_last_successful_run_exists() {
    // Given
    Instant lastRun = Instant.parse("2026-06-27T04:00:00Z");
    when(syncRunService.findLastSuccessfulRunAt(SOURCE_ID)).thenReturn(Optional.of(lastRun));
    when(realtConnector.fetchDelta(lastRun)).thenReturn(List.of(buildListing("ext-1")));
    when(listingIngestionService.ingestBatch(any(), any())).thenReturn(new BatchIngestResult(1, 0, 0));

    // When
    job.runDeltaSync();

    // Then — delta fetch is used, not full fetch
    verify(realtConnector).fetchDelta(lastRun);
    verify(realtConnector, never()).fetch();
  }

  @Test
  void should_record_delta_sync_run_as_delta_type_after_successful_fetch() {
    // Given
    Instant lastRun = Instant.parse("2026-06-27T04:00:00Z");
    when(syncRunService.findLastSuccessfulRunAt(SOURCE_ID)).thenReturn(Optional.of(lastRun));
    when(realtConnector.fetchDelta(lastRun)).thenReturn(List.of(buildListing("ext-1")));
    when(listingIngestionService.ingestBatch(any(), any())).thenReturn(new BatchIngestResult(1, 0, 0));

    // When
    job.runDeltaSync();

    // Then
    ArgumentCaptor<SyncRunRequest> captor = ArgumentCaptor.forClass(SyncRunRequest.class);
    verify(syncRunService).record(captor.capture());
    SyncRunRequest recorded = captor.getValue();
    assertThat(recorded.syncType()).isEqualTo(SyncType.DELTA);
    assertThat(recorded.status()).isEqualTo(SyncRunStatus.SUCCESS);
    assertThat(recorded.listingsFetched()).isEqualTo(1);
    assertThat(recorded.listingsAdded()).isEqualTo(1);
  }

  @Test
  void should_record_zero_count_delta_run_when_no_new_listings_found() {
    // Given
    Instant lastRun = Instant.parse("2026-06-27T04:00:00Z");
    when(syncRunService.findLastSuccessfulRunAt(SOURCE_ID)).thenReturn(Optional.of(lastRun));
    when(realtConnector.fetchDelta(lastRun)).thenReturn(List.of());

    // When
    job.runDeltaSync();

    // Then — empty result still recorded as successful DELTA run
    ArgumentCaptor<SyncRunRequest> captor = ArgumentCaptor.forClass(SyncRunRequest.class);
    verify(syncRunService).record(captor.capture());
    SyncRunRequest recorded = captor.getValue();
    assertThat(recorded.syncType()).isEqualTo(SyncType.DELTA);
    assertThat(recorded.status()).isEqualTo(SyncRunStatus.SUCCESS);
    assertThat(recorded.listingsFetched()).isZero();
    verify(listingIngestionService, never()).ingestBatch(any(), any());
  }

  // -------------------------------------------------------------------------
  // Full fallback — no prior successful run
  // -------------------------------------------------------------------------

  @Test
  void should_run_full_sync_when_no_successful_run_exists() {
    // Given
    when(syncRunService.findLastSuccessfulRunAt(SOURCE_ID)).thenReturn(Optional.empty());
    when(realtConnector.fetch()).thenReturn(List.of(buildListing("ext-1")));
    when(listingIngestionService.ingestBatch(any(), any())).thenReturn(new BatchIngestResult(1, 0, 0));
    when(listingIngestionService.applyMissedSyncPenalty(any(), any())).thenReturn(0);

    // When
    job.runDeltaSync();

    // Then — full fetch is used, not delta
    verify(realtConnector).fetch();
    verify(realtConnector, never()).fetchDelta(any());
  }

  @Test
  void should_record_full_sync_run_when_falling_back_to_full_fetch() {
    // Given
    when(syncRunService.findLastSuccessfulRunAt(SOURCE_ID)).thenReturn(Optional.empty());
    when(realtConnector.fetch()).thenReturn(List.of(buildListing("ext-1")));
    when(listingIngestionService.ingestBatch(any(), any())).thenReturn(new BatchIngestResult(1, 0, 0));
    when(listingIngestionService.applyMissedSyncPenalty(any(), any())).thenReturn(0);

    // When
    job.runDeltaSync();

    // Then — recorded as FULL, not DELTA
    ArgumentCaptor<SyncRunRequest> captor = ArgumentCaptor.forClass(SyncRunRequest.class);
    verify(syncRunService).record(captor.capture());
    assertThat(captor.getValue().syncType()).isEqualTo(SyncType.FULL);
    assertThat(captor.getValue().status()).isEqualTo(SyncRunStatus.SUCCESS);
  }

  @Test
  void should_skip_full_sync_fallback_when_fetch_returns_empty() {
    // Given — empty fetch on first run
    when(syncRunService.findLastSuccessfulRunAt(SOURCE_ID)).thenReturn(Optional.empty());
    when(realtConnector.fetch()).thenReturn(List.of());

    // When
    job.runDeltaSync();

    // Then — no ingest, no record (guard against mass-deactivation)
    verify(listingIngestionService, never()).ingestBatch(any(), any());
    verify(syncRunService, never()).record(any());
  }

  @Test
  void should_skip_full_sync_fallback_when_full_sync_job_is_already_running() {
    // Given — no prior successful run, but FullSyncJob is currently running on another thread
    when(syncRunService.findLastSuccessfulRunAt(SOURCE_ID)).thenReturn(Optional.empty());
    when(realtFullSyncJob.isRunning()).thenReturn(true);

    // When
    job.runDeltaSync();

    // Then — neither fetch nor ingest are triggered; no SyncRun is recorded
    verify(realtConnector, never()).fetch();
    verify(realtConnector, never()).fetchDelta(any());
    verify(listingIngestionService, never()).ingestBatch(any(), any());
    verify(syncRunService, never()).record(any());
  }

  // -------------------------------------------------------------------------
  // Resilience / error handling
  // -------------------------------------------------------------------------

  @Test
  void should_skip_when_circuit_breaker_is_open() {
    // Given
    when(syncRunService.findLastSuccessfulRunAt(SOURCE_ID)).thenReturn(Optional.empty());
    when(realtConnector.fetch()).thenThrow(CallNotPermittedException.createCallNotPermittedException(
        CircuitBreaker.ofDefaults("test")));

    // When / Then — exception does not propagate and no failure record is written
    assertThatNoException().isThrownBy(() -> job.runDeltaSync());
    verify(syncRunService, never()).record(any());
  }

  @Test
  void should_record_failure_when_delta_fetch_throws_exception() {
    // Given
    Instant lastRun = Instant.parse("2026-06-27T04:00:00Z");
    when(syncRunService.findLastSuccessfulRunAt(SOURCE_ID)).thenReturn(Optional.of(lastRun));
    when(realtConnector.fetchDelta(eq(lastRun))).thenThrow(new RuntimeException("Network error"));

    // When
    job.runDeltaSync();

    // Then — FAILURE recorded for DELTA type
    ArgumentCaptor<SyncRunRequest> captor = ArgumentCaptor.forClass(SyncRunRequest.class);
    verify(syncRunService).record(captor.capture());
    assertThat(captor.getValue().syncType()).isEqualTo(SyncType.DELTA);
    assertThat(captor.getValue().status()).isEqualTo(SyncRunStatus.FAILURE);
  }

  @Test
  void should_not_propagate_exception_when_delta_sync_fails() {
    // Given
    Instant lastRun = Instant.parse("2026-06-27T04:00:00Z");
    when(syncRunService.findLastSuccessfulRunAt(SOURCE_ID)).thenReturn(Optional.of(lastRun));
    when(realtConnector.fetchDelta(any())).thenThrow(new RuntimeException("Unexpected error"));

    // When / Then — exception is caught, job does not crash the scheduler thread
    assertThatNoException().isThrownBy(() -> job.runDeltaSync());
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  private RawListing buildListing(String externalId) {
    return new RawListing(
        externalId, "Test Listing", null,
        "RENT", "APARTMENT",
        BigDecimal.valueOf(500), "USD", null, null,
        2, 3, 9, BigDecimal.valueOf(65),
        "ул. Тестовая, 1", null, null, "Минск",
        "https://realt.by/object/" + externalId + "/",
        Instant.now(),
        List.of(), null, null, null
    );
  }
}
