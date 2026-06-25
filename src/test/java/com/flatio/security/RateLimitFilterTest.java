package com.flatio.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RateLimitFilterTest {

  @Mock
  private RateLimiterRegistry rateLimiterRegistry;

  @Mock
  private RateLimiter rateLimiter;

  @Mock
  private FilterChain filterChain;

  private RateLimitFilter filter;

  @BeforeEach
  void setUp() {
    filter = new RateLimitFilter(rateLimiterRegistry, new ObjectMapper().findAndRegisterModules());
    SecurityContextHolder.clearContext();
  }

  @Test
  void should_pass_through_when_path_is_not_under_api_v1() throws Exception {
    // Given
    var request = new MockHttpServletRequest("GET", "/actuator/health");
    var response = new MockHttpServletResponse();

    // When
    filter.doFilterInternal(request, response, filterChain);

    // Then — no limiter looked up, request proceeds
    verify(filterChain).doFilter(request, response);
    verify(rateLimiterRegistry, never()).rateLimiter(any(String.class), any(String.class));
  }

  @Test
  void should_rate_limit_by_client_ip_for_telegram_auth_endpoint() throws Exception {
    // Given
    var request = new MockHttpServletRequest("POST", "/api/v1/auth/telegram");
    request.setRemoteAddr("203.0.113.5");
    var response = new MockHttpServletResponse();
    when(rateLimiterRegistry.rateLimiter(eq("api-auth-telegram:203.0.113.5"), eq("api-auth-telegram")))
        .thenReturn(rateLimiter);
    when(rateLimiter.acquirePermission()).thenReturn(true);

    // When
    filter.doFilterInternal(request, response, filterChain);

    // Then
    verify(filterChain).doFilter(request, response);
  }

  @Test
  void should_prefer_x_forwarded_for_header_over_remote_addr() throws Exception {
    // Given — request arrives through nginx, real client IP is in X-Forwarded-For
    var request = new MockHttpServletRequest("POST", "/api/v1/auth/telegram");
    request.setRemoteAddr("10.0.0.1");
    request.addHeader("X-Forwarded-For", "198.51.100.7, 10.0.0.1");
    var response = new MockHttpServletResponse();
    when(rateLimiterRegistry.rateLimiter(eq("api-auth-telegram:198.51.100.7"), eq("api-auth-telegram")))
        .thenReturn(rateLimiter);
    when(rateLimiter.acquirePermission()).thenReturn(true);

    // When
    filter.doFilterInternal(request, response, filterChain);

    // Then
    verify(filterChain).doFilter(request, response);
  }

  @Test
  void should_return_429_when_telegram_auth_limit_exceeded() throws Exception {
    // Given
    var request = new MockHttpServletRequest("POST", "/api/v1/auth/telegram");
    request.setRemoteAddr("203.0.113.5");
    var response = new MockHttpServletResponse();
    when(rateLimiterRegistry.rateLimiter(eq("api-auth-telegram:203.0.113.5"), eq("api-auth-telegram")))
        .thenReturn(rateLimiter);
    when(rateLimiter.acquirePermission()).thenReturn(false);

    // When
    filter.doFilterInternal(request, response, filterChain);

    // Then — request never reaches the controller, no implementation details leaked
    verify(filterChain, never()).doFilter(any(), any());
    assertThat(response.getStatus()).isEqualTo(429);
    assertThat(response.getContentAsString()).contains("Rate limit exceeded");
    assertThat(response.getContentAsString()).doesNotContain("RateLimiter").doesNotContain("resilience4j");
  }

  @Test
  void should_rate_limit_by_jwt_subject_for_authenticated_api_paths() throws Exception {
    // Given
    SecurityContextHolder.getContext().setAuthentication(
        new UsernamePasswordAuthenticationToken("42", null, java.util.List.of()));
    var request = new MockHttpServletRequest("GET", "/api/v1/listings");
    var response = new MockHttpServletResponse();
    when(rateLimiterRegistry.rateLimiter(eq("api-authenticated:42"), eq("api-authenticated")))
        .thenReturn(rateLimiter);
    when(rateLimiter.acquirePermission()).thenReturn(true);

    // When
    filter.doFilterInternal(request, response, filterChain);

    // Then
    verify(filterChain).doFilter(request, response);
  }

  @Test
  void should_skip_limiting_when_caller_is_not_authenticated_on_protected_path() throws Exception {
    // Given — no authentication in SecurityContext; Spring Security's own authorization
    // rules will reject the request further down the chain regardless
    var request = new MockHttpServletRequest("GET", "/api/v1/listings");
    var response = new MockHttpServletResponse();

    // When
    filter.doFilterInternal(request, response, filterChain);

    // Then
    verify(filterChain).doFilter(request, response);
    verify(rateLimiterRegistry, never()).rateLimiter(any(String.class), any(String.class));
  }
}
