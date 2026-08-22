package com.flatio.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Servlet filter that extracts and validates a JWT bearer token from every HTTP request.
 *
 * <p>When a valid token is found in the {@code Authorization} header, the filter populates
 * the {@link SecurityContextHolder} with an authenticated principal so that downstream
 * security rules can check the user's identity and roles.
 *
 * <p>Requests without a token, or with an invalid token, are passed through unchanged.
 * Spring Security will then enforce access rules based on the (absent) authentication.
 *
 * <p><b>Revocation (issue #365):</b> authorities are sourced from {@link UserStatusCache}, not
 * from the token's own {@code roles} claim — a role change or deactivation applied through the
 * admin panel therefore takes effect on the user's next request instead of only once the token
 * expires. A token whose subject no longer resolves to an active user is treated the same as
 * a missing token: the request proceeds unauthenticated.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

  private static final String AUTHORIZATION_HEADER = "Authorization";
  private static final String BEARER_PREFIX = "Bearer ";

  private final JwtService jwtService;
  private final UserStatusCache userStatusCache;

  @Override
  protected void doFilterInternal(
      @NonNull HttpServletRequest request,
      @NonNull HttpServletResponse response,
      @NonNull FilterChain filterChain
  ) throws ServletException, IOException {
    var authHeader = request.getHeader(AUTHORIZATION_HEADER);

    if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
      filterChain.doFilter(request, response);
      return;
    }

    var token = authHeader.substring(BEARER_PREFIX.length());

    if (!jwtService.isTokenValid(token)) {
      log.debug("Invalid JWT token received for request: {}", request.getRequestURI());
      filterChain.doFilter(request, response);
      return;
    }

    setAuthentication(token, request.getRequestURI());
    filterChain.doFilter(request, response);
  }

  private void setAuthentication(String token, String requestUri) {
    var subject = jwtService.extractSubject(token);
    var userId = parseUserId(subject);
    if (userId == null) {
      log.debug("JWT subject is not a numeric user id, skipping authentication: subject={}", subject);
      return;
    }

    var statusOpt = userStatusCache.getStatus(userId);
    if (statusOpt.isEmpty() || !statusOpt.get().active()) {
      log.debug("Skipping authentication for inactive or unknown user: userId={}, uri={}", userId, requestUri);
      return;
    }

    var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + statusOpt.get().role().name()));
    var authentication = new UsernamePasswordAuthenticationToken(
        subject, null, authorities
    );
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }

  private Long parseUserId(String subject) {
    try {
      return Long.valueOf(subject);
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
