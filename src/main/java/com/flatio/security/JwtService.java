package com.flatio.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import javax.crypto.SecretKey;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Service for generating and validating JWT access tokens.
 *
 * <p>Tokens are signed with HMAC-SHA using the key configured via {@link JwtProperties}.
 * Claims include the subject (user identifier) and a {@code roles} list claim.
 */
@Service
@Slf4j
public class JwtService {

  private static final String ROLES_CLAIM = "roles";

  private final SecretKey signingKey;
  private final long accessTokenExpirySeconds;

  /**
   * Creates a new {@code JwtService} using the provided JWT configuration properties.
   *
   * @param properties JWT configuration (secret key, expiry duration)
   */
  public JwtService(JwtProperties properties) {
    this.signingKey = Keys.hmacShaKeyFor(properties.secretKey().getBytes(StandardCharsets.UTF_8));
    this.accessTokenExpirySeconds = properties.accessTokenExpiry();
  }

  /**
   * Generates a signed JWT access token for the given subject and roles.
   *
   * @param subject user identifier to embed as the token subject
   * @param roles   list of role strings to embed in the {@code roles} claim
   * @return compact serialized JWT token string
   */
  public String generateToken(String subject, List<String> roles) {
    var now = Instant.now();
    var expiry = now.plusSeconds(accessTokenExpirySeconds);

    return Jwts.builder()
        .subject(subject)
        .claim(ROLES_CLAIM, roles)
        .issuedAt(Date.from(now))
        .expiration(Date.from(expiry))
        .signWith(signingKey)
        .compact();
  }

  /**
   * Extracts the subject claim from a JWT token.
   *
   * @param token compact serialized JWT token string
   * @return subject claim value
   * @throws JwtException if the token is invalid or expired
   */
  public String extractSubject(String token) {
    return parseClaims(token).getSubject();
  }

  /**
   * Validates a JWT token by verifying its signature and expiry.
   *
   * @param token compact serialized JWT token string
   * @return {@code true} if the token is valid and not expired, {@code false} otherwise
   */
  public boolean isTokenValid(String token) {
    try {
      parseClaims(token);
      return true;
    } catch (JwtException | IllegalArgumentException ex) {
      log.debug("JWT validation failed: {}", ex.getMessage());
      return false;
    }
  }

  /**
   * Extracts the roles list from a JWT token.
   *
   * @param token compact serialized JWT token string
   * @return list of role strings, or an empty list if the claim is absent or the token is invalid
   */
  @SuppressWarnings("unchecked")
  public List<String> extractRoles(String token) {
    try {
      var roles = parseClaims(token).get(ROLES_CLAIM);
      if (roles instanceof List<?> list) {
        return list.stream()
            .filter(String.class::isInstance)
            .map(String.class::cast)
            .toList();
      }
      return List.of();
    } catch (JwtException | IllegalArgumentException ex) {
      log.debug("Failed to extract roles from token: {}", ex.getMessage());
      return List.of();
    }
  }

  private Claims parseClaims(String token) {
    return Jwts.parser()
        .verifyWith(signingKey)
        .build()
        .parseSignedClaims(token)
        .getPayload();
  }
}
