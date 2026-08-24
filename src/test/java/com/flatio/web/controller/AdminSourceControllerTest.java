package com.flatio.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flatio.common.exception.SourceNotFoundException;
import com.flatio.domain.source.SyncRunStatus;
import com.flatio.domain.source.SyncType;
import com.flatio.security.JwtAuthenticationFilter;
import com.flatio.security.JwtService;
import com.flatio.security.RateLimitFilter;
import com.flatio.service.AdminSourceService;
import com.flatio.service.AdminSyncRunService;
import com.flatio.web.dto.AdminSourceResponse;
import com.flatio.web.dto.AdminSourceUpdateRequest;
import com.flatio.web.dto.AdminSyncRunResponse;
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

@WebMvcTest({AdminSourceController.class, GlobalExceptionHandler.class})
@WithMockUser(username = "1", roles = "ADMIN")
@TestPropertySource(properties = "JWT_SECRET_KEY=test-secret-key-for-unit-tests-minimum-256-bits-long")
class AdminSourceControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ObjectMapper objectMapper;

  @MockBean
  private AdminSourceService adminSourceService;

  @MockBean
  private AdminSyncRunService adminSyncRunService;

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
  // GET /api/v1/admin/sources
  // -------------------------------------------------------------------------

  @Test
  void should_return_200_with_sources_list() throws Exception {
    // Given
    when(adminSourceService.findAll()).thenReturn(List.of(buildSourceResponse("onliner")));

    // When / Then
    mockMvc.perform(get("/api/v1/admin/sources"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].sourceId").value("onliner"))
        .andExpect(jsonPath("$[0].enabled").value(true));
  }

  // -------------------------------------------------------------------------
  // PATCH /api/v1/admin/sources/{sourceId}
  // -------------------------------------------------------------------------

  @Test
  void should_return_200_when_source_updated() throws Exception {
    // Given
    var request = new AdminSourceUpdateRequest(false);
    when(adminSourceService.update(eq("kufar"), any(), eq(1L))).thenReturn(buildSourceResponse("kufar"));

    // When / Then
    mockMvc.perform(patch("/api/v1/admin/sources/kufar")
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sourceId").value("kufar"));
  }

  @Test
  void should_return_404_when_updating_unknown_source() throws Exception {
    // Given
    var request = new AdminSourceUpdateRequest(false);
    when(adminSourceService.update(eq("unknown"), any(), eq(1L)))
        .thenThrow(new SourceNotFoundException("unknown"));

    // When / Then
    mockMvc.perform(patch("/api/v1/admin/sources/unknown")
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isNotFound());
  }

  // -------------------------------------------------------------------------
  // GET /api/v1/admin/sync-runs
  // -------------------------------------------------------------------------

  @Test
  void should_return_200_with_sync_runs_page() throws Exception {
    // Given
    Page<AdminSyncRunResponse> page = new PageImpl<>(
        List.of(buildSyncRunResponse()), PageRequest.of(0, 20), 1);
    when(adminSyncRunService.search(eq("onliner"), any())).thenReturn(page);

    // When / Then
    mockMvc.perform(get("/api/v1/admin/sync-runs").param("sourceId", "onliner"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].sourceId").value("onliner"))
        .andExpect(jsonPath("$.totalElements").value(1));
  }

  // -------------------------------------------------------------------------
  // GET /api/v1/admin/sync-runs/latest
  // -------------------------------------------------------------------------

  @Test
  void should_return_200_with_latest_sync_runs() throws Exception {
    // Given
    when(adminSyncRunService.findLatestPerSource()).thenReturn(List.of(buildSyncRunResponse()));

    // When / Then
    mockMvc.perform(get("/api/v1/admin/sync-runs/latest"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].sourceId").value("onliner"));
  }

  // -------------------------------------------------------------------------
  // helpers
  // -------------------------------------------------------------------------

  private static AdminSourceResponse buildSourceResponse(String sourceId) {
    return new AdminSourceResponse(
        sourceId, sourceId, "https://" + sourceId + ".by", "BY", true,
        Instant.parse("2026-08-14T09:00:00Z"), Instant.parse("2026-01-10T12:00:00Z")
    );
  }

  private static AdminSyncRunResponse buildSyncRunResponse() {
    return new AdminSyncRunResponse(
        1L, "onliner", SyncType.DELTA, SyncRunStatus.SUCCESS,
        Instant.parse("2026-08-14T14:20:00Z"), Instant.parse("2026-08-14T14:20:42Z"),
        42000L, 340, 12, 28, 0
    );
  }
}
