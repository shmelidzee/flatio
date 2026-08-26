package com.flatio.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flatio.common.exception.FavoriteLimitExceededException;
import com.flatio.common.exception.FavoriteNotFoundException;
import com.flatio.common.exception.ListingNotFoundException;
import com.flatio.security.JwtAuthenticationFilter;
import com.flatio.security.JwtService;
import com.flatio.security.RateLimitFilter;
import com.flatio.service.FavoriteService;
import com.flatio.web.dto.CreateFavoriteRequest;
import com.flatio.web.dto.FavoriteResponse;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({FavoriteController.class, GlobalExceptionHandler.class})
@WithMockUser(username = "1")
@TestPropertySource(properties = "JWT_SECRET_KEY=test-secret-key-for-unit-tests-minimum-256-bits-long")
class FavoriteControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ObjectMapper objectMapper;

  @MockBean
  private FavoriteService favoriteService;

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
  // POST /api/v1/favorites
  // -------------------------------------------------------------------------

  @Test
  void should_return_200_when_favorite_created() throws Exception {
    // Given
    when(favoriteService.create(eq(1L), any())).thenReturn(buildResponse(7L, 42L));

    // When / Then
    mockMvc.perform(post("/api/v1/favorites")
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(new CreateFavoriteRequest(42L))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(7))
        .andExpect(jsonPath("$.listing.id").value(42));
  }

  @Test
  void should_return_400_when_create_listing_id_is_null() throws Exception {
    // Given — request missing the required listingId
    var invalidRequest = new CreateFavoriteRequest(null);

    // When / Then
    mockMvc.perform(post("/api/v1/favorites")
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(invalidRequest)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void should_return_404_when_creating_favorite_for_missing_listing() throws Exception {
    // Given
    when(favoriteService.create(eq(1L), any())).thenThrow(new ListingNotFoundException(99L));

    // When / Then
    mockMvc.perform(post("/api/v1/favorites")
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(new CreateFavoriteRequest(99L))))
        .andExpect(status().isNotFound());
  }

  @Test
  void should_return_422_when_favorites_limit_exceeded() throws Exception {
    // Given
    when(favoriteService.create(eq(1L), any())).thenThrow(new FavoriteLimitExceededException(100));

    // When / Then
    mockMvc.perform(post("/api/v1/favorites")
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(new CreateFavoriteRequest(42L))))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.status").value(422));
  }

  // -------------------------------------------------------------------------
  // GET /api/v1/favorites
  // -------------------------------------------------------------------------

  @Test
  void should_return_200_with_page_when_favorites_exist() throws Exception {
    // Given
    Page<FavoriteResponse> page = new PageImpl<>(
        List.of(buildResponse(7L, 42L)), PageRequest.of(0, 20), 1
    );
    when(favoriteService.findByUser(eq(1L), any())).thenReturn(page);

    // When / Then
    mockMvc.perform(get("/api/v1/favorites"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].id").value(7))
        .andExpect(jsonPath("$.totalElements").value(1));
  }

  // -------------------------------------------------------------------------
  // DELETE /api/v1/favorites/{listingId}
  // -------------------------------------------------------------------------

  @Test
  void should_return_200_when_favorite_deleted() throws Exception {
    // When / Then
    mockMvc.perform(delete("/api/v1/favorites/42").with(csrf()))
        .andExpect(status().isOk());
  }

  @Test
  void should_return_404_when_deleting_favorite_not_found() throws Exception {
    // Given
    doThrow(new FavoriteNotFoundException(99L)).when(favoriteService).delete(1L, 99L);

    // When / Then
    mockMvc.perform(delete("/api/v1/favorites/99").with(csrf()))
        .andExpect(status().isNotFound());
  }

  // -------------------------------------------------------------------------
  // helpers
  // -------------------------------------------------------------------------

  private static FavoriteResponse buildResponse(Long id, Long listingId) {
    return new FavoriteResponse(
        id,
        buildListingSummary(listingId),
        new BigDecimal("75000.00"),
        new BigDecimal("72000.00"),
        new BigDecimal("-3000.00"),
        true,
        false,
        Instant.now()
    );
  }

  private static ListingSummaryResponse buildListingSummary(Long listingId) {
    return new ListingSummaryResponse(
        listingId, "2-комнатная квартира", new BigDecimal("72000.00"), "BYN",
        null, null, 2, "APARTMENT", new BigDecimal("52.30"), "Минск",
        null, null, "realt", Instant.now(), null, null, false
    );
  }
}
