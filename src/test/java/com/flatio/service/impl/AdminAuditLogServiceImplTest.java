package com.flatio.service.impl;

import com.flatio.domain.audit.AdminAuditLog;
import com.flatio.domain.audit.AdminAuditObjectType;
import com.flatio.domain.user.User;
import com.flatio.repository.AdminAuditLogRepository;
import com.flatio.repository.UserRepository;
import com.flatio.web.dto.AdminAuditLogResponse;
import com.flatio.web.mapper.AdminAuditLogMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminAuditLogServiceImplTest {

  @Mock
  private AdminAuditLogRepository adminAuditLogRepository;

  @Mock
  private UserRepository userRepository;

  @Mock
  private AdminAuditLogMapper adminAuditLogMapper;

  @InjectMocks
  private AdminAuditLogServiceImpl adminAuditLogService;

  // -------------------------------------------------------------------------
  // record
  // -------------------------------------------------------------------------

  @Test
  void should_save_new_entry_when_recording_an_action() {
    // When
    adminAuditLogService.record("updateListingStatus", AdminAuditObjectType.LISTING, "42", 1L);

    // Then
    ArgumentCaptor<AdminAuditLog> captor = ArgumentCaptor.forClass(AdminAuditLog.class);
    verify(adminAuditLogRepository).save(captor.capture());
    AdminAuditLog saved = captor.getValue();
    assertThat(saved.getAction()).isEqualTo("updateListingStatus");
    assertThat(saved.getObjectType()).isEqualTo(AdminAuditObjectType.LISTING);
    assertThat(saved.getObjectId()).isEqualTo("42");
    assertThat(saved.getAdminId()).isEqualTo(1L);
  }

  // -------------------------------------------------------------------------
  // findRecent
  // -------------------------------------------------------------------------

  @Test
  void should_enrich_entries_with_admin_display_name() {
    // Given
    var entry = buildEntry(1L, 7L);
    var pageable = PageRequest.of(0, 20);
    when(adminAuditLogRepository.findAll(pageable)).thenReturn(new PageImpl<>(List.of(entry)));
    var admin = new User();
    admin.setId(7L);
    admin.setDisplayName("Иван Петров");
    when(userRepository.findAllById(List.of(7L))).thenReturn(List.of(admin));
    var response = mock(AdminAuditLogResponse.class);
    when(adminAuditLogMapper.toResponse(entry, "Иван Петров")).thenReturn(response);

    // When
    Page<AdminAuditLogResponse> result = adminAuditLogService.findRecent(pageable);

    // Then
    assertThat(result.getContent()).containsExactly(response);
  }

  @Test
  void should_pass_null_display_name_when_admin_no_longer_exists() {
    // Given
    var entry = buildEntry(2L, 99L);
    var pageable = PageRequest.of(0, 20);
    when(adminAuditLogRepository.findAll(pageable)).thenReturn(new PageImpl<>(List.of(entry)));
    when(userRepository.findAllById(List.of(99L))).thenReturn(List.of());

    // When
    adminAuditLogService.findRecent(pageable);

    // Then
    verify(adminAuditLogMapper).toResponse(eq(entry), eq(null));
  }

  @Test
  void should_return_empty_page_when_no_entries_recorded() {
    // Given
    var pageable = PageRequest.of(0, 20);
    when(adminAuditLogRepository.findAll(pageable)).thenReturn(new PageImpl<>(List.of()));

    // When
    Page<AdminAuditLogResponse> result = adminAuditLogService.findRecent(pageable);

    // Then
    assertThat(result.getContent()).isEmpty();
  }

  // -------------------------------------------------------------------------
  // helpers
  // -------------------------------------------------------------------------

  private static AdminAuditLog buildEntry(Long id, Long adminId) {
    var entry = new AdminAuditLog();
    entry.setId(id);
    entry.setAdminId(adminId);
    entry.setAction("updateUser");
    entry.setObjectType(AdminAuditObjectType.USER);
    entry.setObjectId(String.valueOf(adminId));
    return entry;
  }
}
