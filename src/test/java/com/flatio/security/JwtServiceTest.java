package com.flatio.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

  private static final String TEST_SECRET =
      "test-secret-key-for-unit-tests-minimum-256-bits-long";

  private JwtService jwtService;

  @BeforeEach
  void setUp() {
    jwtService = new JwtService(new JwtProperties(TEST_SECRET, 3600L));
  }

  @Test
  void should_return_valid_token_when_generated_with_subject_and_roles() {
    // When
    var token = jwtService.generateToken("user123", List.of("ADMIN", "USER"));

    // Then
    assertThat(token).isNotBlank();
    assertThat(token.split("\\.")).hasSize(3);
  }

  @Test
  void should_return_true_when_token_is_valid() {
    // Given
    var token = jwtService.generateToken("user123", List.of("USER"));

    // When
    var result = jwtService.isTokenValid(token);

    // Then
    assertThat(result).isTrue();
  }

  @Test
  void should_return_false_when_token_is_expired() {
    // Given — service configured with negative expiry to produce already-expired token
    var expiredService = new JwtService(new JwtProperties(TEST_SECRET, -3600L));
    var expiredToken = expiredService.generateToken("user123", List.of("USER"));

    // When
    var result = jwtService.isTokenValid(expiredToken);

    // Then
    assertThat(result).isFalse();
  }

  @Test
  void should_return_false_when_token_has_invalid_signature() {
    // Given — token signed with a different key
    var otherSecret = "other-secret-key-for-unit-tests-minimum-256-bits-longX";
    var otherService = new JwtService(new JwtProperties(otherSecret, 3600L));
    var foreignToken = otherService.generateToken("user123", List.of("USER"));

    // When
    var result = jwtService.isTokenValid(foreignToken);

    // Then
    assertThat(result).isFalse();
  }

  @Test
  void should_return_false_when_token_is_null() {
    // When
    var result = jwtService.isTokenValid(null);

    // Then
    assertThat(result).isFalse();
  }

  @Test
  void should_return_false_when_token_is_blank() {
    // When
    var result = jwtService.isTokenValid("   ");

    // Then
    assertThat(result).isFalse();
  }

  @Test
  void should_extract_subject_when_token_is_valid() {
    // Given
    var token = jwtService.generateToken("user-42", List.of("USER"));

    // When
    var subject = jwtService.extractSubject(token);

    // Then
    assertThat(subject).isEqualTo("user-42");
  }

  @Test
  void should_extract_roles_when_roles_claim_present() {
    // Given
    var token = jwtService.generateToken("user-42", List.of("ADMIN", "USER"));

    // When
    var roles = jwtService.extractRoles(token);

    // Then
    assertThat(roles).containsExactlyInAnyOrder("ADMIN", "USER");
  }

  @Test
  void should_return_empty_roles_when_roles_claim_absent() {
    // Given — token built without roles claim
    var signingKey = Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8));
    var now = Instant.now();
    var tokenWithoutRoles = Jwts.builder()
        .subject("user-42")
        .issuedAt(Date.from(now))
        .expiration(Date.from(now.plusSeconds(3600)))
        .signWith(signingKey)
        .compact();

    // When
    var roles = jwtService.extractRoles(tokenWithoutRoles);

    // Then
    assertThat(roles).isEmpty();
  }

  @Test
  void should_throw_when_extract_subject_called_on_expired_token() {
    // Given
    var expiredService = new JwtService(new JwtProperties(TEST_SECRET, -3600L));
    var expiredToken = expiredService.generateToken("user123", List.of("USER"));

    // When / Then
    assertThatThrownBy(() -> jwtService.extractSubject(expiredToken))
        .isInstanceOf(io.jsonwebtoken.JwtException.class);
  }
}
