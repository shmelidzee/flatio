package com.flatio.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flatio.common.exception.ListingNotFoundException;
import com.flatio.domain.listing.ListingStatus;
import com.flatio.security.JwtAuthenticationFilter;
import com.flatio.security.JwtService;
import com.flatio.security.RateLimitFilter;
import com.flatio.service.AdminListingService;
import com.flatio.web.dto.AdminListingUpdateRequest;
import com.flatio.web.dto.ListingSummaryResponse;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.math.BigDecimal;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({AdminListingController.class, GlobalExceptionHandler.class})
@WithMockUser(username = "1", roles = "ADMIN")
@TestPropertySource(properties = "JWT_SECRET_KEY=test-secret-key-for-unit-tests-minimum-256-bits-long")
class AdminListingControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ObjectMapper objectMapper;

  @MockBean
  private AdminListingService adminListingService;

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
  // GET /api/v1/admin/listings
  // -------------------------------------------------------------------------

  @Test
  void should_return_200_with_listings_page() throws Exception {
    // Given
    Page<ListingSummaryResponse> page = new PageImpl<>(
        List.of(buildSummary(1L)), PageRequest.of(0, 20), 1);
    when(adminListingService.search(any(), any())).thenReturn(page);

    // When / Then
    mockMvc.perform(get("/api/v1/admin/listings").param("status", "INACTIVE"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].id").value(1))
        .andExpect(jsonPath("$.totalElements").value(1));
  }

  @Test
  void should_return_400_when_status_is_invalid() throws Exception {
    // When / Then — not one of ACTIVE/INACTIVE/REPOSTED
    mockMvc.perform(get("/api/v1/admin/listings").param("status", "NOT_A_STATUS"))
        .andExpect(status().isBadRequest());
  }

  // -------------------------------------------------------------------------
  // PATCH /api/v1/admin/listings/{id}
  // -------------------------------------------------------------------------

  @Test
  void should_return_200_when_listing_status_updated() throws Exception {
    // Given
    var request = new AdminListingUpdateRequest(ListingStatus.INACTIVE);
    when(adminListingService.updateStatus(eq(5L), eq(ListingStatus.INACTIVE), eq("1")))
        .thenReturn(buildSummary(5L));

    // When / Then
    mockMvc.perform(patch("/api/v1/admin/listings/5")
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(5));
  }

  @Test
  void should_return_404_when_updating_unknown_listing() throws Exception {
    // Given
    var request = new AdminListingUpdateRequest(ListingStatus.INACTIVE);
    when(adminListingService.updateStatus(eq(99L), any(), any()))
        .thenThrow(new ListingNotFoundException(99L));

    // When / Then
    mockMvc.perform(patch("/api/v1/admin/listings/99")
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isNotFound());
  }

  @Test
  void should_return_400_when_status_field_is_missing() throws Exception {
    // When / Then — body with null status fails @NotNull validation
    mockMvc.perform(patch("/api/v1/admin/listings/5")
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isBadRequest());
  }

  // -------------------------------------------------------------------------
  // DELETE /api/v1/admin/listings/{id}/duplicate-group
  // -------------------------------------------------------------------------

  @Test
  void should_return_200_when_duplicate_group_unlinked() throws Exception {
    // When / Then
    mockMvc.perform(delete("/api/v1/admin/listings/5/duplicate-group").with(csrf()))
        .andExpect(status().isOk());
  }

  @Test
  void should_return_404_when_unlinking_unknown_listing() throws Exception {
    // Given
    doThrow(new ListingNotFoundException(99L)).when(adminListingService)
        .unlinkDuplicateGroup(eq(99L), any());

    // When / Then
    mockMvc.perform(delete("/api/v1/admin/listings/99/duplicate-group").with(csrf()))
        .andExpect(status().isNotFound());
  }

  // -------------------------------------------------------------------------
  // helpers
  // -------------------------------------------------------------------------

  private static ListingSummaryResponse buildSummary(Long id) {
    return new ListingSummaryResponse(
        id, "2-комн. квартира", BigDecimal.valueOf(500), "USD", null, null,
        2, "APARTMENT", BigDecimal.valueOf(54), "Минск", "Центральный",
        "ул. Немига 5", "kufar", Instant.now(), null,
        "https://kufar.by/1", false
    );
  }
}
