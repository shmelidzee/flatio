package com.flatio.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flatio.common.exception.BlacklistEntryNotFoundException;
import com.flatio.common.exception.BlacklistInvalidValueException;
import com.flatio.common.exception.BlacklistKeywordLimitExceededException;
import com.flatio.common.exception.ListingNotFoundException;
import com.flatio.domain.blacklist.BlacklistEntryType;
import com.flatio.security.JwtAuthenticationFilter;
import com.flatio.security.JwtService;
import com.flatio.security.RateLimitFilter;
import com.flatio.service.BlacklistService;
import com.flatio.web.dto.BlacklistEntryResponse;
import com.flatio.web.dto.CreateBlacklistEntryRequest;
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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({BlacklistController.class, GlobalExceptionHandler.class})
@WithMockUser(username = "1")
@TestPropertySource(properties = "JWT_SECRET_KEY=test-secret-key-for-unit-tests-minimum-256-bits-long")
class BlacklistControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ObjectMapper objectMapper;

  @MockBean
  private BlacklistService blacklistService;

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
  // POST /api/v1/blacklist
  // -------------------------------------------------------------------------

  @Test
  void should_return_200_when_entry_created() throws Exception {
    // Given
    when(blacklistService.create(eq(1L), any())).thenReturn(buildResponse(7L, BlacklistEntryType.KEYWORD, "novostroyka"));

    // When / Then
    mockMvc.perform(post("/api/v1/blacklist")
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(new CreateBlacklistEntryRequest(BlacklistEntryType.KEYWORD, "novostroyka"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(7))
        .andExpect(jsonPath("$.type").value("KEYWORD"))
        .andExpect(jsonPath("$.value").value("novostroyka"));
  }

  @Test
  void should_return_400_when_create_type_is_missing() throws Exception {
    // Given — request missing the required type field
    var invalidRequest = new CreateBlacklistEntryRequest(null, "novostroyka");

    // When / Then
    mockMvc.perform(post("/api/v1/blacklist")
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(invalidRequest)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void should_return_400_when_create_value_is_blank() throws Exception {
    // Given — request with a blank value, rejected by @NotBlank
    var invalidRequest = new CreateBlacklistEntryRequest(BlacklistEntryType.KEYWORD, "  ");

    // When / Then
    mockMvc.perform(post("/api/v1/blacklist")
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(invalidRequest)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void should_return_400_when_value_format_invalid_for_type() throws Exception {
    // Given — service rejects a non-numeric value for a LISTING entry
    when(blacklistService.create(eq(1L), any()))
        .thenThrow(new BlacklistInvalidValueException(BlacklistEntryType.LISTING, "abc"));

    // When / Then
    mockMvc.perform(post("/api/v1/blacklist")
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(new CreateBlacklistEntryRequest(BlacklistEntryType.LISTING, "abc"))))
        .andExpect(status().isBadRequest());
  }

  @Test
  void should_return_404_when_creating_entry_for_missing_listing() throws Exception {
    // Given
    when(blacklistService.create(eq(1L), any())).thenThrow(new ListingNotFoundException(99L));

    // When / Then
    mockMvc.perform(post("/api/v1/blacklist")
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(new CreateBlacklistEntryRequest(BlacklistEntryType.LISTING, "99"))))
        .andExpect(status().isNotFound());
  }

  @Test
  void should_return_422_when_keyword_limit_exceeded() throws Exception {
    // Given
    when(blacklistService.create(eq(1L), any())).thenThrow(new BlacklistKeywordLimitExceededException(20));

    // When / Then
    mockMvc.perform(post("/api/v1/blacklist")
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(new CreateBlacklistEntryRequest(BlacklistEntryType.KEYWORD, "novostroyka"))))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.status").value(422));
  }

  // -------------------------------------------------------------------------
  // GET /api/v1/blacklist
  // -------------------------------------------------------------------------

  @Test
  void should_return_200_with_page_when_entries_exist() throws Exception {
    // Given
    Page<BlacklistEntryResponse> page = new PageImpl<>(
        List.of(buildResponse(7L, BlacklistEntryType.KEYWORD, "novostroyka")), PageRequest.of(0, 20), 1
    );
    when(blacklistService.findByUser(eq(1L), isNull(), any())).thenReturn(page);

    // When / Then
    mockMvc.perform(get("/api/v1/blacklist"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].id").value(7))
        .andExpect(jsonPath("$.totalElements").value(1));
  }

  @Test
  void should_return_200_with_filtered_page_when_type_param_given() throws Exception {
    // Given
    Page<BlacklistEntryResponse> page = new PageImpl<>(
        List.of(buildResponse(9L, BlacklistEntryType.SOURCE, "5")), PageRequest.of(0, 20), 1
    );
    when(blacklistService.findByUser(eq(1L), eq(BlacklistEntryType.SOURCE), any())).thenReturn(page);

    // When / Then
    mockMvc.perform(get("/api/v1/blacklist").param("type", "SOURCE"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].type").value("SOURCE"));
  }

  // -------------------------------------------------------------------------
  // DELETE /api/v1/blacklist/{id}
  // -------------------------------------------------------------------------

  @Test
  void should_return_200_when_entry_deleted() throws Exception {
    // When / Then
    mockMvc.perform(delete("/api/v1/blacklist/7").with(csrf()))
        .andExpect(status().isOk());
  }

  @Test
  void should_return_404_when_deleting_entry_not_found() throws Exception {
    // Given
    doThrow(new BlacklistEntryNotFoundException(99L)).when(blacklistService).delete(1L, 99L);

    // When / Then
    mockMvc.perform(delete("/api/v1/blacklist/99").with(csrf()))
        .andExpect(status().isNotFound());
  }

  // -------------------------------------------------------------------------
  // helpers
  // -------------------------------------------------------------------------

  private static BlacklistEntryResponse buildResponse(Long id, BlacklistEntryType type, String value) {
    return new BlacklistEntryResponse(id, type, value, Instant.now());
  }
}
