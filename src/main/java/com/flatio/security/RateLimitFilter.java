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
  private static final String ADMIN_PREFIX = "/api/v1/admin/";
  private static final String AUTH_TELEGRAM_LIMITER = "api-auth-telegram";
  private static final String AUTHENTICATED_LIMITER = "api-authenticated";
  private static final String ADMIN_LIMITER = "api-admin";
  private static final String REAL_IP_HEADER = "X-Real-IP";

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

    String limiterName = resolveLimiterName(request.getRequestURI());
    var limiter = rateLimiterRegistry.rateLimiter(limiterName + ":" + key, limiterName);

    if (!limiter.acquirePermission()) {
      log.debug("Rate limit exceeded: limiter={}, key={}, path={}", limiterName, key, request.getRequestURI());
      writeTooManyRequests(request, response);
      return;
    }

    filterChain.doFilter(request, response);
  }

  /**
   * Picks the Resilience4j limiter configuration name for the given request path.
   *
   * @param path the request URI, already confirmed to start with {@link #API_PREFIX}
   * @return {@link #AUTH_TELEGRAM_LIMITER} for auth endpoints, {@link #ADMIN_LIMITER} for
   *     {@code /api/v1/admin/**}, otherwise {@link #AUTHENTICATED_LIMITER}
   */
  private String resolveLimiterName(String path) {
    if (path.startsWith(AUTH_PREFIX)) {
      return AUTH_TELEGRAM_LIMITER;
    }
    return path.startsWith(ADMIN_PREFIX) ? ADMIN_LIMITER : AUTHENTICATED_LIMITER;
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
    // X-Real-IP is set by nginx to $remote_addr (the actual TCP peer) and cannot be spoofed
    // by the client. X-Forwarded-For is deliberately NOT used here: nginx's
    // $proxy_add_x_forwarded_for *appends* to whatever the client already sent rather than
    // replacing it, so a client could set its own X-Forwarded-For and pick a fresh key on
    // every request, bypassing the limiter entirely.
    String realIp = request.getHeader(REAL_IP_HEADER);
    return realIp != null && !realIp.isBlank() ? realIp.strip() : request.getRemoteAddr();
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
