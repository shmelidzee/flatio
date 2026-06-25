package com.flatio.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flatio.web.dto.ErrorResponse;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Applies per-caller rate limits to public and authenticated REST endpoints.
 *
 * <p>Unlike the {@code @RateLimiter} annotation used on connectors (one shared limiter per
 * outbound integration), inbound requests must be limited per caller — one abusive IP or user
 * must not exhaust the quota for everyone else. {@link RateLimiterRegistry#rateLimiter(String,
 * String)} is used to lazily create one {@code RateLimiter} instance per caller, all sharing the
 * configuration registered under a fixed instance name in {@code application.yml}.
 *
 * <p>Must run after {@link JwtAuthenticationFilter} so that {@link SecurityContextHolder} is
 * already populated when this filter resolves the caller's identity for authenticated routes.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RateLimitFilter extends OncePerRequestFilter {

  private static final String API_PREFIX = "/api/v1/";
  private static final String AUTH_PREFIX = "/api/v1/auth/";
  private static final String AUTH_TELEGRAM_LIMITER = "api-auth-telegram";
  private static final String AUTHENTICATED_LIMITER = "api-authenticated";
  private static final String FORWARDED_FOR_HEADER = "X-Forwarded-For";

  private final RateLimiterRegistry rateLimiterRegistry;
  private final ObjectMapper objectMapper;

  @Override
  protected void doFilterInternal(
      @NonNull HttpServletRequest request,
      @NonNull HttpServletResponse response,
      @NonNull FilterChain filterChain
  ) throws ServletException, IOException {
    String key = resolveLimiterKey(request);
    if (key == null) {
      // Not a rate-limited route, or an unauthenticated call to a protected route —
      // the latter is rejected by Spring Security's authorization rules regardless.
      filterChain.doFilter(request, response);
      return;
    }

    String limiterName = request.getRequestURI().startsWith(AUTH_PREFIX) ? AUTH_TELEGRAM_LIMITER : AUTHENTICATED_LIMITER;
    var limiter = rateLimiterRegistry.rateLimiter(limiterName + ":" + key, limiterName);

    if (!limiter.acquirePermission()) {
      log.debug("Rate limit exceeded: limiter={}, key={}, path={}", limiterName, key, request.getRequestURI());
      writeTooManyRequests(request, response);
      return;
    }

    filterChain.doFilter(request, response);
  }

  private String resolveLimiterKey(HttpServletRequest request) {
    String path = request.getRequestURI();
    if (!path.startsWith(API_PREFIX)) {
      return null;
    }
    if (path.startsWith(AUTH_PREFIX)) {
      return clientIp(request);
    }
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    return authentication != null && authentication.isAuthenticated() ? authentication.getName() : null;
  }

  private String clientIp(HttpServletRequest request) {
    String forwardedFor = request.getHeader(FORWARDED_FOR_HEADER);
    if (forwardedFor != null && !forwardedFor.isBlank()) {
      return forwardedFor.split(",")[0].strip();
    }
    return request.getRemoteAddr();
  }

  private void writeTooManyRequests(HttpServletRequest request, HttpServletResponse response) throws IOException {
    var body = new ErrorResponse(
        Instant.now(),
        HttpStatus.TOO_MANY_REQUESTS.value(),
        HttpStatus.TOO_MANY_REQUESTS.getReasonPhrase(),
        "Rate limit exceeded, try again later",
        request.getRequestURI(),
        List.of()
    );
    response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.getWriter().write(objectMapper.writeValueAsString(body));
  }
}
