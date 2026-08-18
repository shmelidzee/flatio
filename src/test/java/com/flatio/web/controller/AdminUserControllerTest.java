package com.flatio.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flatio.common.exception.SelfRoleChangeForbiddenException;
import com.flatio.common.exception.UserNotFoundException;
import com.flatio.domain.user.UserRole;
import com.flatio.security.JwtAuthenticationFilter;
import com.flatio.security.JwtService;
import com.flatio.security.RateLimitFilter;
import com.flatio.service.AdminUserService;
import com.flatio.web.dto.AdminUserResponse;
import com.flatio.web.dto.AdminUserUpdateRequest;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({AdminUserController.class, GlobalExceptionHandler.class})
@WithMockUser(username = "1", roles = "ADMIN")
@TestPropertySource(properties = "JWT_SECRET_KEY=test-secret-key-for-unit-tests-minimum-256-bits-long")
class AdminUserControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ObjectMapper objectMapper;

  @MockBean
  private AdminUserService adminUserService;

  @MockBean
  private JwtService jwtService;

  @MockBean
  private JwtAuthenticationFilter jwtAuthenticationFilter;

  @MockBean
  private RateLimitFilter rateLimitFilter;

  @BeforeEach
  void setUp() throws Exception {
    passThroughFilter(jwtAuthenticationFilter);
    passThroughFilter(rateLimitFilter);
  }

  private void passThroughFilter(Filter filter) throws Exception {
    doAnswer(invocation -> {
      var chain = (FilterChain) invocation.getArgument(2);
      chain.doFilter(
          (HttpServletRequest) invocation.getArgument(0),
          (HttpServletResponse) invocation.getArgument(1)
      );
      return null;
    }).when(filter)
        .doFilter(any(HttpServletRequest.class), any(HttpServletResponse.class), any(FilterChain.class));
  }

  // -------------------------------------------------------------------------
  // GET /api/v1/admin/users
  // -------------------------------------------------------------------------

  @Test
  void should_return_200_with_users_page() throws Exception {
    // Given
    Page<AdminUserResponse> page = new PageImpl<>(
        List.of(buildResponse(1L, UserRole.USER)), PageRequest.of(0, 20), 1);
    when(adminUserService.search(any(), any())).thenReturn(page);

    // When / Then
    mockMvc.perform(get("/api/v1/admin/users").param("active", "true"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].id").value(1))
        .andExpect(jsonPath("$.totalElements").value(1));
  }

  @Test
  void should_return_400_when_role_filter_is_invalid() throws Exception {
    // When / Then — not one of USER/PRO/ADMIN
    mockMvc.perform(get("/api/v1/admin/users").param("role", "NOT_A_ROLE"))
        .andExpect(status().isBadRequest());
  }

  // -------------------------------------------------------------------------
  // PATCH /api/v1/admin/users/{id}
  // -------------------------------------------------------------------------

  @Test
  void should_return_200_when_user_updated() throws Exception {
    // Given
    var request = new AdminUserUpdateRequest(false, null);
    when(adminUserService.update(eq(5L), any(), eq(1L))).thenReturn(buildResponse(5L, UserRole.USER));

    // When / Then
    mockMvc.perform(patch("/api/v1/admin/users/5")
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(5));
  }

  @Test
  void should_return_404_when_updating_unknown_user() throws Exception {
    // Given
    var request = new AdminUserUpdateRequest(false, null);
    when(adminUserService.update(eq(99L), any(), eq(1L))).thenThrow(new UserNotFoundException(99L));

    // When / Then
    mockMvc.perform(patch("/api/v1/admin/users/99")
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isNotFound());
  }

  @Test
  void should_return_403_when_admin_downgrades_own_role() throws Exception {
    // Given
    var request = new AdminUserUpdateRequest(null, UserRole.USER);
    when(adminUserService.update(eq(1L), any(), eq(1L)))
        .thenThrow(new SelfRoleChangeForbiddenException(1L));

    // When / Then
    mockMvc.perform(patch("/api/v1/admin/users/1")
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isForbidden());
  }

  // -------------------------------------------------------------------------
  // helpers
  // -------------------------------------------------------------------------

  private static AdminUserResponse buildResponse(Long id, UserRole role) {
    return new AdminUserResponse(id, "Иван Петров", "ivan@example.com", role, true, Instant.now(), Instant.now());
  }
}
