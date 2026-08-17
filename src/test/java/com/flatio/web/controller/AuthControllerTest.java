package com.flatio.web.controller;

import com.flatio.common.exception.AdminAccessDeniedException;
import com.flatio.common.exception.InvalidTelegramAuthException;
import com.flatio.security.JwtService;
import com.flatio.service.AuthService;
import com.flatio.web.dto.AuthResponse;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests {@link AuthController} request mapping, validation, and exception handling in
 * isolation. Security filters are disabled here ({@code addFilters = false}) — the fact that
 * this endpoint is actually reachable without a JWT (the {@code permitAll} rule in
 * {@link com.flatio.security.SecurityConfig}) is verified separately in
 * {@code SecurityConfigTest}, which loads the real security filter chain.
 */
@WebMvcTest({AuthController.class, GlobalExceptionHandler.class})
@AutoConfigureMockMvc(addFilters = false)
class AuthControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockBean
  private AuthService authService;

  @MockBean
  private JwtService jwtService;

  @MockBean
  private RateLimiterRegistry rateLimiterRegistry;

  @Test
  void should_return_200_with_token_when_init_data_is_valid() throws Exception {
    // Given
    when(authService.authenticateWithTelegram(eq("valid-init-data")))
        .thenReturn(new AuthResponse("signed-jwt", 3600L));

    // When / Then
    mockMvc.perform(post("/api/v1/auth/telegram")
            .contentType("application/json")
            .content("{\"initData\":\"valid-init-data\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accessToken").value("signed-jwt"))
        .andExpect(jsonPath("$.expiresIn").value(3600));
  }

  @Test
  void should_return_401_when_init_data_is_invalid() throws Exception {
    // Given
    when(authService.authenticateWithTelegram(eq("bad-init-data")))
        .thenThrow(new InvalidTelegramAuthException("initData signature is invalid"));

    // When / Then
    mockMvc.perform(post("/api/v1/auth/telegram")
            .contentType("application/json")
            .content("{\"initData\":\"bad-init-data\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.status").value(401))
        .andExpect(jsonPath("$.message").value("initData signature is invalid"));
  }

  @Test
  void should_return_400_when_init_data_is_blank() throws Exception {
    // When / Then
    mockMvc.perform(post("/api/v1/auth/telegram")
            .contentType("application/json")
            .content("{\"initData\":\"\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400));
  }

  @Test
  void should_return_400_when_request_body_is_missing_init_data_field() throws Exception {
    // When / Then
    mockMvc.perform(post("/api/v1/auth/telegram")
            .contentType("application/json")
            .content("{}"))
        .andExpect(status().isBadRequest());
  }

  // -------------------------------------------------------------------------
  // POST /api/v1/auth/telegram-login-widget
  // -------------------------------------------------------------------------

  private static final String WIDGET_PAYLOAD = """
      {"id":99,"first_name":"Admin","auth_date":1700000000,"hash":"signed-hash"}""";

  @Test
  void should_return_200_with_token_when_login_widget_payload_is_valid() throws Exception {
    // Given
    when(authService.authenticateWithTelegramLoginWidget(any()))
        .thenReturn(new AuthResponse("admin-jwt", 3600L));

    // When / Then
    mockMvc.perform(post("/api/v1/auth/telegram-login-widget")
            .contentType("application/json")
            .content(WIDGET_PAYLOAD))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accessToken").value("admin-jwt"))
        .andExpect(jsonPath("$.expiresIn").value(3600));
  }

  @Test
  void should_return_401_when_login_widget_signature_is_invalid() throws Exception {
    // Given
    when(authService.authenticateWithTelegramLoginWidget(any()))
        .thenThrow(new InvalidTelegramAuthException("Telegram Login Widget signature is invalid"));

    // When / Then
    mockMvc.perform(post("/api/v1/auth/telegram-login-widget")
            .contentType("application/json")
            .content(WIDGET_PAYLOAD))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.status").value(401));
  }

  @Test
  void should_return_403_when_login_widget_user_is_not_admin() throws Exception {
    // Given
    when(authService.authenticateWithTelegramLoginWidget(any()))
        .thenThrow(new AdminAccessDeniedException("This Telegram account does not have admin-panel access"));

    // When / Then
    mockMvc.perform(post("/api/v1/auth/telegram-login-widget")
            .contentType("application/json")
            .content(WIDGET_PAYLOAD))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.status").value(403));
  }

  @Test
  void should_return_400_when_login_widget_request_body_is_missing_required_fields() throws Exception {
    // When / Then
    mockMvc.perform(post("/api/v1/auth/telegram-login-widget")
            .contentType("application/json")
            .content("{}"))
        .andExpect(status().isBadRequest());
  }
}
