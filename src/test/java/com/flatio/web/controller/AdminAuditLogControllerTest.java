package com.flatio.web.controller;

import com.flatio.domain.audit.AdminAuditObjectType;
import com.flatio.security.JwtAuthenticationFilter;
import com.flatio.security.JwtService;
import com.flatio.security.RateLimitFilter;
import com.flatio.service.AdminAuditLogService;
import com.flatio.web.dto.AdminAuditLogResponse;
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
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({AdminAuditLogController.class, GlobalExceptionHandler.class})
@WithMockUser(username = "1", roles = "ADMIN")
@TestPropertySource(properties = "JWT_SECRET_KEY=test-secret-key-for-unit-tests-minimum-256-bits-long")
class AdminAuditLogControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockBean
  private AdminAuditLogService adminAuditLogService;

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
  // GET /api/v1/admin/audit-log
  // -------------------------------------------------------------------------

  @Test
  void should_return_200_with_audit_log_page() throws Exception {
    // Given
    Page<AdminAuditLogResponse> page = new PageImpl<>(
        List.of(buildEntry()), PageRequest.of(0, 20), 1);
    when(adminAuditLogService.findRecent(any())).thenReturn(page);

    // When / Then
    mockMvc.perform(get("/api/v1/admin/audit-log"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].action").value("updateListingStatus"))
        .andExpect(jsonPath("$.content[0].adminDisplayName").value("Иван Петров"))
        .andExpect(jsonPath("$.totalElements").value(1));
  }

  @Test
  void should_return_200_with_empty_page_when_no_actions_recorded() throws Exception {
    // Given
    when(adminAuditLogService.findRecent(any())).thenReturn(new PageImpl<>(List.of()));

    // When / Then
    mockMvc.perform(get("/api/v1/admin/audit-log"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content").isEmpty());
  }

  // -------------------------------------------------------------------------
  // helpers
  // -------------------------------------------------------------------------

  private static AdminAuditLogResponse buildEntry() {
    return new AdminAuditLogResponse(
        1L, 7L, "Иван Петров", "updateListingStatus", AdminAuditObjectType.LISTING, "42",
        Instant.parse("2026-08-18T14:20:00Z")
    );
  }
}
