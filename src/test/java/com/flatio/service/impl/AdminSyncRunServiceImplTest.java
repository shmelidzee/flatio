package com.flatio.service.impl;

import com.flatio.domain.source.Source;
import com.flatio.domain.source.SyncRun;
import com.flatio.repository.SourceRepository;
import com.flatio.repository.SyncRunRepository;
import com.flatio.web.dto.AdminSyncRunResponse;
import com.flatio.web.mapper.AdminSyncRunMapper;
import java.util.List;
import java.util.Optional;
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
  private SourceRepository sourceRepository;

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
  void should_return_latest_run_for_each_source_that_has_run() {
    // Given
    Source onliner = buildSource("onliner");
    Source domovita = buildSource("domovita");
    when(sourceRepository.findAll()).thenReturn(List.of(onliner, domovita));

    SyncRun onlinerRun = new SyncRun();
    when(syncRunRepository.findTopBySourceIdOrderByStartedAtDesc("onliner"))
        .thenReturn(Optional.of(onlinerRun));
    when(syncRunRepository.findTopBySourceIdOrderByStartedAtDesc("domovita"))
        .thenReturn(Optional.empty());

    AdminSyncRunResponse response = mock(AdminSyncRunResponse.class);
    when(adminSyncRunMapper.toResponse(onlinerRun)).thenReturn(response);

    // When
    List<AdminSyncRunResponse> result = adminSyncRunService.findLatestPerSource();

    // Then — only the source with a recorded run appears
    assertThat(result).containsExactly(response);
  }

  private Source buildSource(String code) {
    var source = new Source();
    source.setCode(code);
    return source;
  }
}
