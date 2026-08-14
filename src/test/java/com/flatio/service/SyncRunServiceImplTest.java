package com.flatio.service;

import com.flatio.domain.source.Source;
import com.flatio.domain.source.SyncRun;
import com.flatio.domain.source.SyncRunStatus;
import com.flatio.domain.source.SyncType;
import com.flatio.repository.SourceRepository;
import com.flatio.repository.SyncRunRepository;
import com.flatio.service.domain.BatchIngestResult;
import com.flatio.service.domain.SyncRunRequest;
import com.flatio.service.impl.SyncRunServiceImpl;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SyncRunServiceImplTest {

  @Mock
  private SyncRunRepository syncRunRepository;

  @Mock
  private SourceRepository sourceRepository;

  @InjectMocks
  private SyncRunServiceImpl syncRunService;

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
  void should_delete_old_runs_for_every_registered_source() {
    // Given
    Source onliner = buildSource("ONLINER");
    Source realt = buildSource("REALT");
    when(sourceRepository.findAll()).thenReturn(List.of(onliner, realt));
    when(syncRunRepository.deleteOldRunsBeyondLimit("ONLINER", 100)).thenReturn(5);
    when(syncRunRepository.deleteOldRunsBeyondLimit("REALT", 100)).thenReturn(0);

    // When
    syncRunService.cleanupOldRuns(100);

    // Then — cleanup runs once per source, using each source's code
    verify(syncRunRepository).deleteOldRunsBeyondLimit("ONLINER", 100);
    verify(syncRunRepository).deleteOldRunsBeyondLimit("REALT", 100);
  }

  @Test
  void should_not_query_repository_when_no_sources_registered() {
    // Given
    when(sourceRepository.findAll()).thenReturn(List.of());

    // When
    syncRunService.cleanupOldRuns(100);

    // Then
    verify(syncRunRepository, never()).deleteOldRunsBeyondLimit(any(), anyInt());
  }

  private Source buildSource(String code) {
    var source = new Source();
    source.setCode(code);
    return source;
  }
}
