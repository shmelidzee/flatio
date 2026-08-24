package com.flatio.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flatio.common.exception.SubscriptionLimitExceededException;
import com.flatio.common.exception.SubscriptionNotFoundException;
import com.flatio.domain.subscription.DeliveryMode;
import com.flatio.domain.subscription.SubscriptionChannelType;
import com.flatio.domain.subscription.TriggerType;
import com.flatio.security.JwtAuthenticationFilter;
import com.flatio.security.JwtService;
import com.flatio.security.RateLimitFilter;
import com.flatio.service.SubscriptionService;
import com.flatio.web.dto.CreateSubscriptionRequest;
import com.flatio.web.dto.SubscriptionResponse;
import com.flatio.web.dto.SubscriptionSearchCriteria;
import com.flatio.web.dto.UpdateSubscriptionRequest;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({SubscriptionController.class, GlobalExceptionHandler.class})
@WithMockUser(username = "1")
@TestPropertySource(properties = "JWT_SECRET_KEY=test-secret-key-for-unit-tests-minimum-256-bits-long")
class SubscriptionControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ObjectMapper objectMapper;

  @MockBean
  private SubscriptionService subscriptionService;

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
  // POST /api/v1/subscriptions
  // -------------------------------------------------------------------------

  @Test
  void should_return_200_when_subscription_created() throws Exception {
    // Given
    when(subscriptionService.create(eq(1L), any())).thenReturn(buildResponse(1L));

    // When / Then
    mockMvc.perform(post("/api/v1/subscriptions")
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(buildCreateRequest())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(1))
        .andExpect(jsonPath("$.active").value(true));
  }

  @Test
  void should_return_400_when_create_name_is_blank() throws Exception {
    // Given — request with blank name
    var invalidRequest = new CreateSubscriptionRequest(
        "", buildCriteria(), Set.of(TriggerType.NEW_LISTING), DeliveryMode.REALTIME,
        SubscriptionChannelType.TELEGRAM, null, null, null
    );

    // When / Then
    mockMvc.perform(post("/api/v1/subscriptions")
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(invalidRequest)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void should_return_400_when_create_name_exceeds_max_length() throws Exception {
    // Given — name longer than the 255-char DB column limit (issue #385)
    var invalidRequest = new CreateSubscriptionRequest(
        "a".repeat(256), buildCriteria(), Set.of(TriggerType.NEW_LISTING), DeliveryMode.REALTIME,
        SubscriptionChannelType.TELEGRAM, null, null, null
    );

    // When / Then — rejected by @Size validation before reaching the service/DB
    mockMvc.perform(post("/api/v1/subscriptions")
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(invalidRequest)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void should_return_400_when_create_triggers_is_empty() throws Exception {
    // Given — request with no triggers
    var invalidRequest = new CreateSubscriptionRequest(
        "My filter", buildCriteria(), Set.of(), DeliveryMode.REALTIME,
        SubscriptionChannelType.TELEGRAM, null, null, null
    );

    // When / Then
    mockMvc.perform(post("/api/v1/subscriptions")
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(invalidRequest)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void should_return_422_when_subscription_limit_exceeded() throws Exception {
    // Given
    when(subscriptionService.create(eq(1L), any())).thenThrow(new SubscriptionLimitExceededException(3));

    // When / Then
    mockMvc.perform(post("/api/v1/subscriptions")
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(buildCreateRequest())))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.status").value(422));
  }

  // -------------------------------------------------------------------------
  // GET /api/v1/subscriptions
  // -------------------------------------------------------------------------

  @Test
  void should_return_200_with_page_when_subscriptions_exist() throws Exception {
    // Given
    Page<SubscriptionResponse> page = new PageImpl<>(
        List.of(buildResponse(1L)), PageRequest.of(0, 20), 1
    );
    when(subscriptionService.findByUser(eq(1L), any())).thenReturn(page);

    // When / Then
    mockMvc.perform(get("/api/v1/subscriptions"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].id").value(1))
        .andExpect(jsonPath("$.totalElements").value(1));
  }

  // -------------------------------------------------------------------------
  // GET /api/v1/subscriptions/{id}
  // -------------------------------------------------------------------------

  @Test
  void should_return_200_when_subscription_found() throws Exception {
    // Given
    when(subscriptionService.findByIdForUser(1L, 5L)).thenReturn(buildResponse(5L));

    // When / Then
    mockMvc.perform(get("/api/v1/subscriptions/5"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(5));
  }

  @Test
  void should_return_404_when_subscription_not_found() throws Exception {
    // Given
    when(subscriptionService.findByIdForUser(1L, 99L)).thenThrow(new SubscriptionNotFoundException(99L));

    // When / Then
    mockMvc.perform(get("/api/v1/subscriptions/99"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").value("Subscription not found: 99"));
  }

  // -------------------------------------------------------------------------
  // PUT /api/v1/subscriptions/{id}
  // -------------------------------------------------------------------------

  @Test
  void should_return_200_when_subscription_updated() throws Exception {
    // Given
    when(subscriptionService.update(eq(1L), eq(5L), any())).thenReturn(buildResponse(5L));

    // When / Then
    mockMvc.perform(put("/api/v1/subscriptions/5")
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(buildUpdateRequest())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(5));
  }

  @Test
  void should_return_400_when_update_name_is_blank() throws Exception {
    // Given
    var invalidRequest = new UpdateSubscriptionRequest(
        "", buildCriteria(), Set.of(TriggerType.NEW_LISTING), DeliveryMode.REALTIME,
        SubscriptionChannelType.TELEGRAM, null, null, null
    );

    // When / Then
    mockMvc.perform(put("/api/v1/subscriptions/5")
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(invalidRequest)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void should_return_400_when_update_name_exceeds_max_length() throws Exception {
    // Given — name longer than the 255-char DB column limit (issue #385)
    var invalidRequest = new UpdateSubscriptionRequest(
        "a".repeat(256), buildCriteria(), Set.of(TriggerType.NEW_LISTING), DeliveryMode.REALTIME,
        SubscriptionChannelType.TELEGRAM, null, null, null
    );

    // When / Then
    mockMvc.perform(put("/api/v1/subscriptions/5")
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(invalidRequest)))
        .andExpect(status().isBadRequest());
  }

  // -------------------------------------------------------------------------
  // PATCH /api/v1/subscriptions/{id}/pause and /resume
  // -------------------------------------------------------------------------

  @Test
  void should_return_200_when_subscription_paused() throws Exception {
    // Given
    when(subscriptionService.pause(1L, 5L)).thenReturn(buildResponse(5L));

    // When / Then
    mockMvc.perform(patch("/api/v1/subscriptions/5/pause").with(csrf()))
        .andExpect(status().isOk());
  }

  @Test
  void should_return_200_when_subscription_resumed() throws Exception {
    // Given
    when(subscriptionService.resume(1L, 5L)).thenReturn(buildResponse(5L));

    // When / Then
    mockMvc.perform(patch("/api/v1/subscriptions/5/resume").with(csrf()))
        .andExpect(status().isOk());
  }

  @Test
  void should_return_422_when_resume_exceeds_limit() throws Exception {
    // Given
    when(subscriptionService.resume(1L, 5L)).thenThrow(new SubscriptionLimitExceededException(3));

    // When / Then
    mockMvc.perform(patch("/api/v1/subscriptions/5/resume").with(csrf()))
        .andExpect(status().isUnprocessableEntity());
  }

  // -------------------------------------------------------------------------
  // DELETE /api/v1/subscriptions/{id}
  // -------------------------------------------------------------------------

  @Test
  void should_return_200_when_subscription_deleted() throws Exception {
    // When / Then
    mockMvc.perform(delete("/api/v1/subscriptions/5").with(csrf()))
        .andExpect(status().isOk());
  }

  @Test
  void should_return_404_when_deleting_subscription_not_found() throws Exception {
    // Given
    doThrow(new SubscriptionNotFoundException(99L)).when(subscriptionService).delete(1L, 99L);

    // When / Then
    mockMvc.perform(delete("/api/v1/subscriptions/99").with(csrf()))
        .andExpect(status().isNotFound());
  }

  // -------------------------------------------------------------------------
  // helpers
  // -------------------------------------------------------------------------

  private static SubscriptionSearchCriteria buildCriteria() {
    return new SubscriptionSearchCriteria(
        null, null, null, "Минск", null,
        BigDecimal.valueOf(500), BigDecimal.valueOf(1500), 2, null, null
    );
  }

  private static CreateSubscriptionRequest buildCreateRequest() {
    return new CreateSubscriptionRequest(
        "2-комнатные в центре", buildCriteria(), Set.of(TriggerType.NEW_LISTING),
        DeliveryMode.REALTIME, SubscriptionChannelType.TELEGRAM, null, null, null
    );
  }

  private static UpdateSubscriptionRequest buildUpdateRequest() {
    return new UpdateSubscriptionRequest(
        "2-комнатные в центре", buildCriteria(), Set.of(TriggerType.NEW_LISTING),
        DeliveryMode.REALTIME, SubscriptionChannelType.TELEGRAM, null, null, null
    );
  }

  private static SubscriptionResponse buildResponse(Long id) {
    return new SubscriptionResponse(
        id, "2-комнатные в центре", true, buildCriteria(), Set.of(TriggerType.NEW_LISTING),
        DeliveryMode.REALTIME, SubscriptionChannelType.TELEGRAM, new BigDecimal("5.00"),
        null, null, Instant.now(), Instant.now()
    );
  }
}
