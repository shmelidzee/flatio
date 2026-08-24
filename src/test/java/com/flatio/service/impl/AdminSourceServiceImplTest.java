package com.flatio.service.impl;

import com.flatio.common.exception.SourceNotFoundException;
import com.flatio.domain.audit.AdminAuditObjectType;
import com.flatio.domain.source.Source;
import com.flatio.repository.SourceRepository;
import com.flatio.repository.SyncRunRepository;
import com.flatio.service.AdminAuditLogService;
import com.flatio.service.SyncRunService;
import com.flatio.web.dto.AdminSourceResponse;
import com.flatio.web.dto.AdminSourceUpdateRequest;
import com.flatio.web.mapper.AdminSourceMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminSourceServiceImplTest {

  @Mock
  private SourceRepository sourceRepository;

  @Mock
  private SyncRunRepository syncRunRepository;

  @Mock
  private SyncRunService syncRunService;

  @Mock
  private AdminSourceMapper adminSourceMapper;

  @Mock
  private AdminAuditLogService adminAuditLogService;

  @InjectMocks
  private AdminSourceServiceImpl adminSourceService;

  // -------------------------------------------------------------------------
  // findAll
  // -------------------------------------------------------------------------

  @Test
  void should_return_source_with_last_sync_time_when_found() {
    // Given
    Source source = buildSource("onliner", true);
    when(sourceRepository.findAll(any(Sort.class))).thenReturn(List.of(source));
    Instant lastSync = Instant.parse("2026-08-14T09:00:00Z");
    when(syncRunRepository.findLastSuccessfulFinishedAtGroupedBySource())
        .thenReturn(List.<Object[]>of(new Object[] {"onliner", lastSync}));
    AdminSourceResponse expected = mock(AdminSourceResponse.class);
    when(adminSourceMapper.toResponse(source, lastSync)).thenReturn(expected);

    // When
    List<AdminSourceResponse> result = adminSourceService.findAll();

    // Then — a single bulk query is used, no per-source lookup
    assertThat(result).containsExactly(expected);
    verify(adminSourceMapper).toResponse(source, lastSync);
    verify(syncRunService, never()).findLastSuccessfulRunAt(any());
  }

  @Test
  void should_pass_null_last_sync_when_source_never_synced() {
    // Given
    Source source = buildSource("domovita", false);
    when(sourceRepository.findAll(any(Sort.class))).thenReturn(List.of(source));
    when(syncRunRepository.findLastSuccessfulFinishedAtGroupedBySource()).thenReturn(List.of());

    // When
    adminSourceService.findAll();

    // Then
    verify(adminSourceMapper).toResponse(source, null);
  }

  // -------------------------------------------------------------------------
  // update
  // -------------------------------------------------------------------------

  @Test
  void should_update_enabled_flag_when_present_in_request() {
    // Given
    Source source = buildSource("kufar", true);
    when(sourceRepository.findByCode("kufar")).thenReturn(Optional.of(source));
    when(syncRunService.findLastSuccessfulRunAt("kufar")).thenReturn(Optional.empty());
    var request = new AdminSourceUpdateRequest(false);

    // When
    adminSourceService.update("kufar", request, 1L);

    // Then
    assertThat(source.isActive()).isFalse();
    verify(sourceRepository).save(source);
    verify(adminAuditLogService).record("updateSource", AdminAuditObjectType.SOURCE, "kufar", 1L);
  }

  @Test
  void should_leave_enabled_unchanged_when_request_field_is_null() {
    // Given
    Source source = buildSource("kufar", true);
    when(sourceRepository.findByCode("kufar")).thenReturn(Optional.of(source));
    when(syncRunService.findLastSuccessfulRunAt("kufar")).thenReturn(Optional.empty());
    var request = new AdminSourceUpdateRequest(null);

    // When
    adminSourceService.update("kufar", request, 1L);

    // Then
    assertThat(source.isActive()).isTrue();
  }

  @Test
  void should_throw_exception_when_source_not_found() {
    // Given
    when(sourceRepository.findByCode("unknown")).thenReturn(Optional.empty());
    var request = new AdminSourceUpdateRequest(true);

    // When / Then
    assertThatThrownBy(() -> adminSourceService.update("unknown", request, 1L))
        .isInstanceOf(SourceNotFoundException.class)
        .hasMessageContaining("unknown");
  }

  // -------------------------------------------------------------------------
  // helpers
  // -------------------------------------------------------------------------

  private Source buildSource(String code, boolean active) {
    var source = new Source();
    source.setCode(code);
    source.setName(code);
    source.setActive(active);
    return source;
  }
}
