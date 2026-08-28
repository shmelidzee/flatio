package com.flatio.service;

import com.flatio.domain.source.SyncRun;
import com.flatio.domain.source.SyncRunStatus;
import com.flatio.domain.source.SyncType;
import com.flatio.repository.SyncRunRepository;
import com.flatio.service.domain.BatchIngestResult;
import com.flatio.service.domain.SyncRunRequest;
import com.flatio.service.impl.SyncRunServiceImpl;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SyncRunServiceImplTest {

  @Mock
  private SyncRunRepository syncRunRepository;

  private SimpleMeterRegistry meterRegistry;
  private SyncRunServiceImpl syncRunService;

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();
    syncRunService = new SyncRunServiceImpl(syncRunRepository, meterRegistry);
  }

  // -------------------------------------------------------------------------
  // record
  // -------------------------------------------------------------------------

  @Test
  void should_persist_sync_run_with_correct_fields_when_record_called() {
    // Given
    Instant start = Instant.parse("2026-06-12T10:00:00Z");
    Instant finish = Instant.parse("2026-06-12T10:00:30Z");
    var request = SyncRunRequest.success("ONLINER", SyncType.DELTA, start, finish,
        20, new BatchIngestResult(5, 3, 1));

    // When
    syncRunService.record(request);

    // Then — repository.save is called with a correctly mapped entity
    ArgumentCaptor<SyncRun> captor = ArgumentCaptor.forClass(SyncRun.class);
    verify(syncRunRepository).save(captor.capture());

    SyncRun saved = captor.getValue();
    assertThat(saved.getSourceId()).isEqualTo("ONLINER");
    assertThat(saved.getSyncType()).isEqualTo(SyncType.DELTA);
    assertThat(saved.getStatus()).isEqualTo(SyncRunStatus.SUCCESS);
    assertThat(saved.getStartedAt()).isEqualTo(start);
    assertThat(saved.getFinishedAt()).isEqualTo(finish);
    assertThat(saved.getListingsFetched()).isEqualTo(20);
    assertThat(saved.getListingsAdded()).isEqualTo(5);
    assertThat(saved.getListingsUpdated()).isEqualTo(3);
    assertThat(saved.getListingsErrors()).isEqualTo(1);

    var timer = meterRegistry.find("flatio.sync.duration")
        .tags("source", "ONLINER", "syncType", "DELTA", "status", "SUCCESS").timer();
    assertThat(timer).isNotNull();
    assertThat(timer.count()).isEqualTo(1);
    assertThat(timer.totalTime(TimeUnit.SECONDS)).isEqualTo(30.0);
    var counter = meterRegistry.find("flatio.sync.runs")
        .tags("source", "ONLINER", "syncType", "DELTA", "status", "SUCCESS").counter();
    assertThat(counter).isNotNull();
    assertThat(counter.count()).isEqualTo(1.0);
  }

  @Test
  void should_persist_failure_run_with_zero_counts_when_record_called_with_failure() {
    // Given
    Instant start = Instant.parse("2026-06-12T10:00:00Z");
    Instant finish = Instant.parse("2026-06-12T10:00:05Z");
    var request = SyncRunRequest.failure("ONLINER", SyncType.FULL, start, finish);

    // When
    syncRunService.record(request);

    // Then
    ArgumentCaptor<SyncRun> captor = ArgumentCaptor.forClass(SyncRun.class);
    verify(syncRunRepository).save(captor.capture());

    SyncRun saved = captor.getValue();
    assertThat(saved.getStatus()).isEqualTo(SyncRunStatus.FAILURE);
    assertThat(saved.getListingsFetched()).isZero();
    assertThat(saved.getListingsAdded()).isZero();
    assertThat(saved.getListingsErrors()).isZero();

    var counter = meterRegistry.find("flatio.sync.runs")
        .tags("source", "ONLINER", "syncType", "FULL", "status", "FAILURE").counter();
    assertThat(counter).isNotNull();
    assertThat(counter.count()).isEqualTo(1.0);
  }

  // -------------------------------------------------------------------------
  // findLastSuccessfulRunAt
  // -------------------------------------------------------------------------

  @Test
  void should_return_finished_at_of_last_successful_run() {
    // Given
    Instant expectedFinish = Instant.parse("2026-06-12T09:55:00Z");
    SyncRun lastRun = new SyncRun();
    lastRun.setFinishedAt(expectedFinish);
    when(syncRunRepository.findTopByStatusOrderByFinishedAtDesc(SyncRunStatus.SUCCESS))
        .thenReturn(Optional.of(lastRun));

    // When
    Optional<Instant> result = syncRunService.findLastSuccessfulRunAt();

    // Then
    assertThat(result).isPresent().contains(expectedFinish);
  }

  @Test
  void should_return_empty_when_no_successful_run_exists() {
    // Given
    when(syncRunRepository.findTopByStatusOrderByFinishedAtDesc(SyncRunStatus.SUCCESS))
        .thenReturn(Optional.empty());

    // When
    Optional<Instant> result = syncRunService.findLastSuccessfulRunAt();

    // Then
    assertThat(result).isEmpty();
  }

  @Test
  void should_query_repository_with_success_status() {
    // Given
    when(syncRunRepository.findTopByStatusOrderByFinishedAtDesc(any())).thenReturn(Optional.empty());

    // When
    syncRunService.findLastSuccessfulRunAt();

    // Then — only SUCCESS status is used for the freshness check
    verify(syncRunRepository).findTopByStatusOrderByFinishedAtDesc(SyncRunStatus.SUCCESS);
  }

  // -------------------------------------------------------------------------
  // findLastSuccessfulRunAt(String sourceId)
  // -------------------------------------------------------------------------

  @Test
  void should_return_finished_at_of_last_successful_run_for_source() {
    // Given
    Instant expectedFinish = Instant.parse("2026-06-15T08:00:00Z");
    SyncRun lastRun = new SyncRun();
    lastRun.setFinishedAt(expectedFinish);
    when(syncRunRepository.findTopBySourceIdAndStatusOrderByFinishedAtDesc("REALT", SyncRunStatus.SUCCESS))
        .thenReturn(Optional.of(lastRun));

    // When
    Optional<Instant> result = syncRunService.findLastSuccessfulRunAt("REALT");

    // Then
    assertThat(result).isPresent().contains(expectedFinish);
  }

  @Test
  void should_return_empty_when_no_successful_run_exists_for_source() {
    // Given
    when(syncRunRepository.findTopBySourceIdAndStatusOrderByFinishedAtDesc(eq("REALT"), any()))
        .thenReturn(Optional.empty());

    // When
    Optional<Instant> result = syncRunService.findLastSuccessfulRunAt("REALT");

    // Then
    assertThat(result).isEmpty();
  }

  @Test
  void should_query_repository_with_source_id_and_success_status() {
    // Given
    when(syncRunRepository.findTopBySourceIdAndStatusOrderByFinishedAtDesc(any(), any()))
        .thenReturn(Optional.empty());

    // When
    syncRunService.findLastSuccessfulRunAt("REALT");

    // Then — correct source and SUCCESS status are passed
    verify(syncRunRepository).findTopBySourceIdAndStatusOrderByFinishedAtDesc("REALT", SyncRunStatus.SUCCESS);
  }

  // -------------------------------------------------------------------------
  // cleanupOldRuns
  // -------------------------------------------------------------------------

  @Test
  void should_delete_old_runs_across_all_sources_in_one_call() {
    // Given
    when(syncRunRepository.deleteOldRunsBeyondLimitForAllSources(100)).thenReturn(5);

    // When
    syncRunService.cleanupOldRuns(100);

    // Then — a single bulk query handles every source, no per-source loop
    verify(syncRunRepository).deleteOldRunsBeyondLimitForAllSources(100);
  }

  @Test
  void should_not_log_when_nothing_deleted() {
    // Given
    when(syncRunRepository.deleteOldRunsBeyondLimitForAllSources(100)).thenReturn(0);

    // When / Then — no exception, single call regardless of result
    syncRunService.cleanupOldRuns(100);
    verify(syncRunRepository).deleteOldRunsBeyondLimitForAllSources(100);
  }
}
