package com.flatio.service.impl;

import com.flatio.common.exception.SourceNotFoundException;
import com.flatio.domain.source.Source;
import com.flatio.repository.SourceRepository;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminSourceServiceImplTest {

  @Mock
  private SourceRepository sourceRepository;

  @Mock
  private SyncRunService syncRunService;

  @Mock
  private AdminSourceMapper adminSourceMapper;

  @InjectMocks
  private AdminSourceServiceImpl adminSourceService;

  // -------------------------------------------------------------------------
  // findAll
  // -------------------------------------------------------------------------

  @Test
  void should_return_source_with_last_sync_time_when_found() {
    // Given
    Source source = buildSource("onliner", true, 60);
    when(sourceRepository.findAll(any(Sort.class))).thenReturn(List.of(source));
    Instant lastSync = Instant.parse("2026-08-14T09:00:00Z");
    when(syncRunService.findLastSuccessfulRunAt("onliner")).thenReturn(Optional.of(lastSync));
    AdminSourceResponse expected = mock(AdminSourceResponse.class);
    when(adminSourceMapper.toResponse(source, lastSync)).thenReturn(expected);

    // When
    List<AdminSourceResponse> result = adminSourceService.findAll();

    // Then
    assertThat(result).containsExactly(expected);
    verify(adminSourceMapper).toResponse(source, lastSync);
  }

  @Test
  void should_pass_null_last_sync_when_source_never_synced() {
    // Given
    Source source = buildSource("domovita", false, 60);
    when(sourceRepository.findAll(any(Sort.class))).thenReturn(List.of(source));
    when(syncRunService.findLastSuccessfulRunAt("domovita")).thenReturn(Optional.empty());

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
    Source source = buildSource("kufar", true, 60);
    when(sourceRepository.findByCode("kufar")).thenReturn(Optional.of(source));
    when(syncRunService.findLastSuccessfulRunAt("kufar")).thenReturn(Optional.empty());
    var request = new AdminSourceUpdateRequest(false, null);

    // When
    adminSourceService.update("kufar", request);

    // Then
    assertThat(source.isActive()).isFalse();
    verify(sourceRepository).save(source);
  }

  @Test
  void should_update_sync_interval_when_present_in_request() {
    // Given
    Source source = buildSource("kufar", true, 60);
    when(sourceRepository.findByCode("kufar")).thenReturn(Optional.of(source));
    when(syncRunService.findLastSuccessfulRunAt("kufar")).thenReturn(Optional.empty());
    var request = new AdminSourceUpdateRequest(null, 90);

    // When
    adminSourceService.update("kufar", request);

    // Then
    assertThat(source.getSyncIntervalMinutes()).isEqualTo(90);
    assertThat(source.isActive()).isTrue();
  }

  @Test
  void should_leave_fields_unchanged_when_request_fields_are_null() {
    // Given
    Source source = buildSource("kufar", true, 60);
    when(sourceRepository.findByCode("kufar")).thenReturn(Optional.of(source));
    when(syncRunService.findLastSuccessfulRunAt("kufar")).thenReturn(Optional.empty());
    var request = new AdminSourceUpdateRequest(null, null);

    // When
    adminSourceService.update("kufar", request);

    // Then
    assertThat(source.isActive()).isTrue();
    assertThat(source.getSyncIntervalMinutes()).isEqualTo(60);
  }

  @Test
  void should_throw_exception_when_source_not_found() {
    // Given
    when(sourceRepository.findByCode("unknown")).thenReturn(Optional.empty());
    var request = new AdminSourceUpdateRequest(true, null);

    // When / Then
    assertThatThrownBy(() -> adminSourceService.update("unknown", request))
        .isInstanceOf(SourceNotFoundException.class)
        .hasMessageContaining("unknown");
  }

  // -------------------------------------------------------------------------
  // helpers
  // -------------------------------------------------------------------------

  private Source buildSource(String code, boolean active, int syncIntervalMinutes) {
    var source = new Source();
    source.setCode(code);
    source.setName(code);
    source.setActive(active);
    source.setSyncIntervalMinutes(syncIntervalMinutes);
    return source;
  }
}
