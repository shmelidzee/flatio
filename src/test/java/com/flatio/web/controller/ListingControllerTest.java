package com.flatio.web.controller;

import com.flatio.common.exception.ListingNotFoundException;
import com.flatio.domain.listing.DealType;
import com.flatio.domain.listing.ListingStatus;
import com.flatio.security.JwtAuthenticationFilter;
import com.flatio.security.JwtService;
import com.flatio.security.RateLimitFilter;
import com.flatio.service.ListingService;
import com.flatio.web.dto.ListingResponse;
import com.flatio.web.dto.ListingSearchCriteria;
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
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({ListingController.class, GlobalExceptionHandler.class})
@WithMockUser(username = "1")
@TestPropertySource(properties = "JWT_SECRET_KEY=test-secret-key-for-unit-tests-minimum-256-bits-long")
class ListingControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockBean
  private ListingService listingService;

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
  // GET /api/v1/listings
  // -------------------------------------------------------------------------

  @Test
  void should_return_200_with_page_when_listings_exist() throws Exception {
    // Given
    var summary = buildSummary(1L);
    Page<ListingSummaryResponse> page = new PageImpl<>(List.of(summary), PageRequest.of(0, 20), 1);
    when(listingService.search(any(), any(), any())).thenReturn(page);

    // When / Then
    mockMvc.perform(get("/api/v1/listings"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].id").value(1))
        .andExpect(jsonPath("$.content[0].title").value("Test listing"))
        .andExpect(jsonPath("$.totalElements").value(1));
  }

  @Test
  void should_return_200_with_empty_page_when_no_listings_match() throws Exception {
    // Given
    Page<ListingSummaryResponse> emptyPage = Page.empty();
    when(listingService.search(any(), any(), any())).thenReturn(emptyPage);

    // When / Then
    mockMvc.perform(get("/api/v1/listings").param("city", "Гродно"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(0));
  }

  @Test
  void should_pass_filter_params_to_service() throws Exception {
    // Given
    Page<ListingSummaryResponse> emptyPage = Page.empty();
    when(listingService.search(any(), any(), any())).thenReturn(emptyPage);

    // When / Then — verify endpoint accepts all filter params without error
    mockMvc.perform(get("/api/v1/listings")
            .param("dealType", "RENT")
            .param("city", "Минск")
            .param("priceMin", "500")
            .param("priceMax", "1500")
            .param("rooms", "2"))
        .andExpect(status().isOk());
  }

  @Test
  @SuppressWarnings("unchecked")
  void should_use_createdAt_desc_sort_by_default() throws Exception {
    // Given
    var pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
    Page<ListingSummaryResponse> emptyPage = Page.empty();
    when(listingService.search(any(), pageableCaptor.capture(), any())).thenReturn(emptyPage);

    // When
    mockMvc.perform(get("/api/v1/listings"))
        .andExpect(status().isOk());

    // Then — default Pageable must have sort createdAt DESC
    Pageable captured = pageableCaptor.getValue();
    Sort.Order order = captured.getSort().getOrderFor("createdAt");
    assertThat(order).isNotNull();
    assertThat(order.getDirection()).isEqualTo(Sort.Direction.DESC);
  }

  @Test
  @SuppressWarnings("unchecked")
  void should_clamp_page_size_when_requested_size_exceeds_max() throws Exception {
    // Given — spring.data.web.pageable.max-page-size is 100 (issue #386)
    var pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
    Page<ListingSummaryResponse> emptyPage = Page.empty();
    when(listingService.search(any(), pageableCaptor.capture(), any())).thenReturn(emptyPage);

    // When — request far exceeds the configured max
    mockMvc.perform(get("/api/v1/listings").param("size", "2000"))
        .andExpect(status().isOk());

    // Then — Spring Data clamps to the configured max, not the requested 2000
    assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(100);
  }

  @Test
  @SuppressWarnings("unchecked")
  void should_pass_authenticated_user_id_to_service_for_blacklist_exclusion() throws Exception {
    // Given
    var userIdCaptor = ArgumentCaptor.forClass(Long.class);
    Page<ListingSummaryResponse> emptyPage = Page.empty();
    when(listingService.search(any(), any(), userIdCaptor.capture())).thenReturn(emptyPage);

    // When
    mockMvc.perform(get("/api/v1/listings"))
        .andExpect(status().isOk());

    // Then — issue #414: the authenticated caller's ID is passed through for blacklist exclusion
    assertThat(userIdCaptor.getValue()).isEqualTo(1L);
  }

  // -------------------------------------------------------------------------
  // GET /api/v1/listings/{id}
  // -------------------------------------------------------------------------

  @Test
  void should_return_200_with_listing_when_found() throws Exception {
    // Given
    var response = buildListingResponse(42L);
    when(listingService.findById(42L)).thenReturn(response);

    // When / Then
    mockMvc.perform(get("/api/v1/listings/42"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(42))
        .andExpect(jsonPath("$.title").value("Test listing"))
        .andExpect(jsonPath("$.priceHistory").isArray());
  }

  @Test
  void should_return_404_when_listing_not_found() throws Exception {
    // Given
    when(listingService.findById(99L)).thenThrow(new ListingNotFoundException(99L));

    // When / Then
    mockMvc.perform(get("/api/v1/listings/99"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.status").value(404))
        .andExpect(jsonPath("$.message").value("Listing not found: 99"));
  }

  @Test
  void should_return_400_when_id_is_not_a_number() throws Exception {
    // When / Then
    mockMvc.perform(get("/api/v1/listings/not-a-number"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400));
  }

  @Test
  void should_return_400_when_deal_type_param_is_invalid() throws Exception {
    // When / Then — binding fails when enum value is not recognised
    mockMvc.perform(get("/api/v1/listings").param("dealType", "TOTALLY_INVALID"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400));
  }

  @Test
  void should_return_200_when_deal_type_filter_is_rent_daily() throws Exception {
    // Given
    Page<ListingSummaryResponse> emptyPage = Page.empty();
    when(listingService.search(any(), any(), any())).thenReturn(emptyPage);

    // When / Then — RENT_DAILY must be accepted as a valid dealType filter value
    mockMvc.perform(get("/api/v1/listings").param("dealType", "RENT_DAILY"))
        .andExpect(status().isOk());
  }

  @Test
  @SuppressWarnings("unchecked")
  void should_bind_rent_daily_deal_type_to_search_criteria() throws Exception {
    // Given
    var criteriaCaptor = ArgumentCaptor.forClass(ListingSearchCriteria.class);
    Page<ListingSummaryResponse> emptyPage = Page.empty();
    when(listingService.search(criteriaCaptor.capture(), any(), any())).thenReturn(emptyPage);

    // When
    mockMvc.perform(get("/api/v1/listings").param("dealType", "RENT_DAILY"))
        .andExpect(status().isOk());

    // Then — service receives RENT_DAILY as the deal type in criteria
    assertThat(criteriaCaptor.getValue().dealType()).isEqualTo(DealType.RENT_DAILY);
  }

  // -------------------------------------------------------------------------
  // helpers
  // -------------------------------------------------------------------------

  private static ListingSummaryResponse buildSummary(Long id) {
    return new ListingSummaryResponse(
        id, "Test listing", BigDecimal.valueOf(75_000), "USD", null, null, 2,
        null, BigDecimal.valueOf(52.5), "Минск", null, null, "realt", Instant.now(), null,
        "https://realt.by/" + id, null
    );
  }

  private static ListingResponse buildListingResponse(Long id) {
    return new ListingResponse(
        id, "ext-1", "realt", "Test listing", null, DealType.SELL, null, "APARTMENT",
        BigDecimal.valueOf(75_000), null, "USD", 2, 5, 9,
        BigDecimal.valueOf(52.5), "ул. Ленина, 1", "Минск", null, null, null,
        true, null, ListingStatus.ACTIVE, "https://realt.by/1", Instant.now(), Instant.now(), List.of(), false
    );
  }
}
