package com.flatio.security;

import java.util.List;
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
    when(jwtService.extractSubject(token)).thenReturn("user-42");
    when(jwtService.extractRoles(token)).thenReturn(List.of("USER"));

    // When
    filter.doFilter(request, response, chain);

    // Then
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    assertThat(authentication).isNotNull();
    assertThat(authentication.getName()).isEqualTo("user-42");
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
  void should_set_multiple_role_authorities_when_token_has_multiple_roles() throws Exception {
    // Given
    var token = "valid.jwt.token";
    var request = new MockHttpServletRequest();
    request.addHeader("Authorization", "Bearer " + token);
    var response = new MockHttpServletResponse();
    var chain = new MockFilterChain();

    when(jwtService.isTokenValid(token)).thenReturn(true);
    when(jwtService.extractSubject(token)).thenReturn("admin-1");
    when(jwtService.extractRoles(token)).thenReturn(List.of("ADMIN", "USER"));

    // When
    filter.doFilter(request, response, chain);

    // Then
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    assertThat(authentication).isNotNull();
    assertThat(authentication.getAuthorities())
        .extracting("authority")
        .containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_USER");
  }

}
