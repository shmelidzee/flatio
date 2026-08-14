package com.flatio.service.impl;

import com.flatio.domain.source.SyncRun;
import com.flatio.repository.SyncRunRepository;
import com.flatio.web.dto.AdminSyncRunResponse;
import com.flatio.web.mapper.AdminSyncRunMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminSyncRunServiceImplTest {

  @Mock
  private SyncRunRepository syncRunRepository;

  @Mock
  private AdminSyncRunMapper adminSyncRunMapper;

  @InjectMocks
  private AdminSyncRunServiceImpl adminSyncRunService;

  // -------------------------------------------------------------------------
  // search
  // -------------------------------------------------------------------------

  @Test
  void should_search_by_source_when_source_id_provided() {
    // Given
    SyncRun run = new SyncRun();
    Pageable pageable = PageRequest.of(0, 20);
    when(syncRunRepository.findBySourceIdOrderByStartedAtDesc("onliner", pageable))
        .thenReturn(new PageImpl<>(List.of(run)));
    AdminSyncRunResponse response = mock(AdminSyncRunResponse.class);
    when(adminSyncRunMapper.toResponse(run)).thenReturn(response);

    // When
    Page<AdminSyncRunResponse> result = adminSyncRunService.search("onliner", pageable);

    // Then
    assertThat(result.getContent()).containsExactly(response);
    verify(syncRunRepository, never()).findAllByOrderByStartedAtDesc(pageable);
  }

  @Test
  void should_search_across_all_sources_when_source_id_omitted() {
    // Given
    SyncRun run = new SyncRun();
    Pageable pageable = PageRequest.of(0, 20);
    when(syncRunRepository.findAllByOrderByStartedAtDesc(pageable))
        .thenReturn(new PageImpl<>(List.of(run)));
    AdminSyncRunResponse response = mock(AdminSyncRunResponse.class);
    when(adminSyncRunMapper.toResponse(run)).thenReturn(response);

    // When
    Page<AdminSyncRunResponse> result = adminSyncRunService.search(null, pageable);

    // Then
    assertThat(result.getContent()).containsExactly(response);
    verify(syncRunRepository, never()).findBySourceIdOrderByStartedAtDesc(any(), any());
  }

  // -------------------------------------------------------------------------
  // findLatestPerSource
  // -------------------------------------------------------------------------

  @Test
  void should_return_latest_run_per_source_from_single_bulk_query() {
    // Given
    SyncRun onlinerRun = new SyncRun();
    when(syncRunRepository.findLatestPerSource()).thenReturn(List.of(onlinerRun));
    AdminSyncRunResponse response = mock(AdminSyncRunResponse.class);
    when(adminSyncRunMapper.toResponseList(List.of(onlinerRun))).thenReturn(List.of(response));

    // When
    List<AdminSyncRunResponse> result = adminSyncRunService.findLatestPerSource();

    // Then — a single bulk query is used, no per-source loop
    assertThat(result).containsExactly(response);
    verify(syncRunRepository).findLatestPerSource();
  }

  @Test
  void should_return_empty_list_when_no_source_has_run_yet() {
    // Given
    when(syncRunRepository.findLatestPerSource()).thenReturn(List.of());
    when(adminSyncRunMapper.toResponseList(List.of())).thenReturn(List.of());

    // When
    List<AdminSyncRunResponse> result = adminSyncRunService.findLatestPerSource();

    // Then
    assertThat(result).isEmpty();
  }
}
