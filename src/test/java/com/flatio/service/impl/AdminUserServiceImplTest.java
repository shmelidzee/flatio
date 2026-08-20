package com.flatio.service.impl;

import com.flatio.common.exception.SelfRoleChangeForbiddenException;
import com.flatio.common.exception.UserNotFoundException;
import com.flatio.domain.audit.AdminAuditObjectType;
import com.flatio.domain.user.User;
import com.flatio.domain.user.UserRole;
import com.flatio.repository.UserRepository;
import com.flatio.security.UserStatusCache;
import com.flatio.service.AdminAuditLogService;
import com.flatio.web.dto.AdminUserResponse;
import com.flatio.web.dto.AdminUserSearchCriteria;
import com.flatio.web.dto.AdminUserUpdateRequest;
import com.flatio.web.mapper.AdminUserMapper;
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
import org.springframework.data.jpa.domain.Specification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminUserServiceImplTest {

  @Mock
  private UserRepository userRepository;

  @Mock
  private AdminUserMapper adminUserMapper;

  @Mock
  private AdminAuditLogService adminAuditLogService;

  @Mock
  private UserStatusCache userStatusCache;

  @InjectMocks
  private AdminUserServiceImpl adminUserService;

  // -------------------------------------------------------------------------
  // search
  // -------------------------------------------------------------------------

  @Test
  void should_return_page_of_users_matching_criteria() {
    // Given
    var pageable = PageRequest.of(0, 20);
    var user = new User();
    var response = mock(AdminUserResponse.class);
    when(userRepository.findAll(any(Specification.class), eq(pageable)))
        .thenReturn(new PageImpl<>(List.of(user)));
    when(adminUserMapper.toResponse(user)).thenReturn(response);

    // When
    Page<AdminUserResponse> result = adminUserService.search(new AdminUserSearchCriteria(null, null), pageable);

    // Then
    assertThat(result.getContent()).containsExactly(response);
  }

  @Test
  void should_return_empty_page_when_no_users_match() {
    // Given
    var pageable = PageRequest.of(0, 20);
    when(userRepository.findAll(any(Specification.class), eq(pageable)))
        .thenReturn(new PageImpl<>(List.of()));

    // When
    Page<AdminUserResponse> result = adminUserService.search(
        new AdminUserSearchCriteria(UserRole.ADMIN, true), pageable);

    // Then
    assertThat(result.getContent()).isEmpty();
  }

  // -------------------------------------------------------------------------
  // update
  // -------------------------------------------------------------------------

  @Test
  void should_update_active_state_without_changing_role() {
    // Given
    var user = new User();
    user.setId(10L);
    user.setRole(UserRole.USER);
    user.setActive(true);
    when(userRepository.findById(10L)).thenReturn(Optional.of(user));
    var response = mock(AdminUserResponse.class);
    when(adminUserMapper.toResponse(user)).thenReturn(response);

    // When
    AdminUserResponse result = adminUserService.update(10L, new AdminUserUpdateRequest(false, null), 1L);

    // Then
    assertThat(user.isActive()).isFalse();
    assertThat(user.getRole()).isEqualTo(UserRole.USER);
    assertThat(result).isSameAs(response);
    verify(userRepository).save(user);
    verify(userStatusCache).evict(10L);
    verify(adminAuditLogService).record("updateUser", AdminAuditObjectType.USER, "10", 1L);
  }

  @Test
  void should_update_role_when_changed_by_a_different_admin() {
    // Given
    var user = new User();
    user.setId(20L);
    user.setRole(UserRole.ADMIN);
    when(userRepository.findById(20L)).thenReturn(Optional.of(user));
    var response = mock(AdminUserResponse.class);
    when(adminUserMapper.toResponse(user)).thenReturn(response);

    // When — admin id=1 downgrades a different admin (id=20)
    adminUserService.update(20L, new AdminUserUpdateRequest(null, UserRole.USER), 1L);

    // Then
    assertThat(user.getRole()).isEqualTo(UserRole.USER);
    verify(userRepository).save(user);
    verify(userStatusCache).evict(20L);
  }

  @Test
  void should_allow_self_role_change_when_not_currently_admin() {
    // Given — self-upgrade from USER to PRO, not currently ADMIN
    var user = new User();
    user.setId(1L);
    user.setRole(UserRole.USER);
    when(userRepository.findById(1L)).thenReturn(Optional.of(user));
    when(adminUserMapper.toResponse(user)).thenReturn(mock(AdminUserResponse.class));

    // When
    adminUserService.update(1L, new AdminUserUpdateRequest(null, UserRole.PRO), 1L);

    // Then
    assertThat(user.getRole()).isEqualTo(UserRole.PRO);
  }

  @Test
  void should_allow_self_role_update_when_new_role_is_still_admin() {
    // Given — no-op re-assignment of ADMIN to self is not a downgrade
    var user = new User();
    user.setId(1L);
    user.setRole(UserRole.ADMIN);
    when(userRepository.findById(1L)).thenReturn(Optional.of(user));
    when(adminUserMapper.toResponse(user)).thenReturn(mock(AdminUserResponse.class));

    // When
    adminUserService.update(1L, new AdminUserUpdateRequest(null, UserRole.ADMIN), 1L);

    // Then
    assertThat(user.getRole()).isEqualTo(UserRole.ADMIN);
  }

  @Test
  void should_forbid_self_role_downgrade_from_admin() {
    // Given
    var user = new User();
    user.setId(1L);
    user.setRole(UserRole.ADMIN);
    when(userRepository.findById(1L)).thenReturn(Optional.of(user));

    // When / Then
    assertThatThrownBy(() -> adminUserService.update(1L, new AdminUserUpdateRequest(null, UserRole.USER), 1L))
        .isInstanceOf(SelfRoleChangeForbiddenException.class)
        .hasMessageContaining("1");
    assertThat(user.getRole()).isEqualTo(UserRole.ADMIN);
    verify(adminAuditLogService, never()).record(any(), any(), any(), any());
    verify(userStatusCache, never()).evict(any());
  }

  @Test
  void should_throw_exception_when_user_not_found_for_update() {
    // Given
    when(userRepository.findById(99L)).thenReturn(Optional.empty());

    // When / Then
    assertThatThrownBy(() -> adminUserService.update(99L, new AdminUserUpdateRequest(false, null), 1L))
        .isInstanceOf(UserNotFoundException.class)
        .hasMessageContaining("99");
  }
}
