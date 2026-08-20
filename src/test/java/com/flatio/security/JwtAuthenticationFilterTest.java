package com.flatio.security;

import com.flatio.domain.user.UserRole;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

  @Mock
  private JwtService jwtService;

  @Mock
  private UserStatusCache userStatusCache;

  @InjectMocks
  private JwtAuthenticationFilter filter;

  @BeforeEach
  void setUp() {
    SecurityContextHolder.clearContext();
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void should_set_authentication_when_valid_bearer_token_present() throws Exception {
    // Given
    var token = "valid.jwt.token";
    var request = new MockHttpServletRequest();
    request.addHeader("Authorization", "Bearer " + token);
    var response = new MockHttpServletResponse();
    var chain = new MockFilterChain();

    when(jwtService.isTokenValid(token)).thenReturn(true);
    when(jwtService.extractSubject(token)).thenReturn("42");
    when(userStatusCache.getStatus(42L)).thenReturn(Optional.of(new UserStatusCache.UserStatus(true, UserRole.USER)));

    // When
    filter.doFilter(request, response, chain);

    // Then
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    assertThat(authentication).isNotNull();
    assertThat(authentication.getName()).isEqualTo("42");
    assertThat(authentication.getAuthorities())
        .extracting("authority")
        .containsExactly("ROLE_USER");
  }

  @Test
  void should_pass_through_when_no_authorization_header() throws Exception {
    // Given
    var request = new MockHttpServletRequest();
    var response = new MockHttpServletResponse();
    var chain = new MockFilterChain();

    // When
    filter.doFilter(request, response, chain);

    // Then
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    verify(jwtService, never()).isTokenValid(any());
  }

  @Test
  void should_pass_through_when_token_is_invalid() throws Exception {
    // Given
    var token = "invalid.jwt.token";
    var request = new MockHttpServletRequest();
    request.addHeader("Authorization", "Bearer " + token);
    var response = new MockHttpServletResponse();
    var chain = new MockFilterChain();

    when(jwtService.isTokenValid(token)).thenReturn(false);

    // When
    filter.doFilter(request, response, chain);

    // Then
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    verify(jwtService, never()).extractSubject(any());
  }

  @Test
  void should_pass_through_when_authorization_header_is_not_bearer() throws Exception {
    // Given
    var request = new MockHttpServletRequest();
    request.addHeader("Authorization", "Basic dXNlcjpwYXNz");
    var response = new MockHttpServletResponse();
    var chain = new MockFilterChain();

    // When
    filter.doFilter(request, response, chain);

    // Then
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    verify(jwtService, never()).isTokenValid(any());
  }

  @Test
  void should_set_admin_role_authority_when_current_db_role_is_admin() throws Exception {
    // Given
    var token = "valid.jwt.token";
    var request = new MockHttpServletRequest();
    request.addHeader("Authorization", "Bearer " + token);
    var response = new MockHttpServletResponse();
    var chain = new MockFilterChain();

    when(jwtService.isTokenValid(token)).thenReturn(true);
    when(jwtService.extractSubject(token)).thenReturn("1");
    when(userStatusCache.getStatus(1L)).thenReturn(Optional.of(new UserStatusCache.UserStatus(true, UserRole.ADMIN)));

    // When
    filter.doFilter(request, response, chain);

    // Then
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    assertThat(authentication).isNotNull();
    assertThat(authentication.getAuthorities())
        .extracting("authority")
        .containsExactly("ROLE_ADMIN");
  }

  @Test
  void should_skip_authentication_when_user_is_deactivated() throws Exception {
    // Given — token is cryptographically valid but the user was deactivated after issuance
    var token = "valid.jwt.token";
    var request = new MockHttpServletRequest();
    request.addHeader("Authorization", "Bearer " + token);
    var response = new MockHttpServletResponse();
    var chain = new MockFilterChain();

    when(jwtService.isTokenValid(token)).thenReturn(true);
    when(jwtService.extractSubject(token)).thenReturn("1");
    when(userStatusCache.getStatus(1L)).thenReturn(Optional.of(new UserStatusCache.UserStatus(false, UserRole.ADMIN)));

    // When
    filter.doFilter(request, response, chain);

    // Then
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
  }

  @Test
  void should_skip_authentication_when_user_no_longer_exists() throws Exception {
    // Given
    var token = "valid.jwt.token";
    var request = new MockHttpServletRequest();
    request.addHeader("Authorization", "Bearer " + token);
    var response = new MockHttpServletResponse();
    var chain = new MockFilterChain();

    when(jwtService.isTokenValid(token)).thenReturn(true);
    when(jwtService.extractSubject(token)).thenReturn("999");
    when(userStatusCache.getStatus(999L)).thenReturn(Optional.empty());

    // When
    filter.doFilter(request, response, chain);

    // Then
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
  }

  @Test
  void should_skip_authentication_when_subject_is_not_numeric() throws Exception {
    // Given
    var token = "valid.jwt.token";
    var request = new MockHttpServletRequest();
    request.addHeader("Authorization", "Bearer " + token);
    var response = new MockHttpServletResponse();
    var chain = new MockFilterChain();

    when(jwtService.isTokenValid(token)).thenReturn(true);
    when(jwtService.extractSubject(token)).thenReturn("not-a-number");

    // When
    filter.doFilter(request, response, chain);

    // Then
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    verify(userStatusCache, never()).getStatus(any());
  }
}
