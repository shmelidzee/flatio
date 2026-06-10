package com.flatio.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
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
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

  private static final String AUTHORIZATION_HEADER = "Authorization";
  private static final String BEARER_PREFIX = "Bearer ";

  private final JwtService jwtService;

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

    setAuthentication(token);
    filterChain.doFilter(request, response);
  }

  private void setAuthentication(String token) {
    var subject = jwtService.extractSubject(token);
    var authorities = jwtService.extractRoles(token).stream()
        .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
        .toList();

    var authentication = new UsernamePasswordAuthenticationToken(
        subject, null, authorities
    );
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }
}
