package com.flatio.service.impl;

import com.flatio.common.exception.AdminAccessDeniedException;
import com.flatio.common.exception.InvalidTelegramAuthException;
import com.flatio.domain.user.User;
import com.flatio.domain.user.UserRole;
import com.flatio.security.JwtProperties;
import com.flatio.security.JwtService;
import com.flatio.security.TelegramInitDataValidator;
import com.flatio.security.TelegramLoginWidgetValidator;
import com.flatio.security.TelegramWebAppUser;
import com.flatio.service.UserService;
import com.flatio.web.dto.TelegramLoginWidgetRequest;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

  @Mock
  private TelegramInitDataValidator initDataValidator;

  @Mock
  private TelegramLoginWidgetValidator loginWidgetValidator;

  @Mock
  private UserService userService;

  @Mock
  private JwtService jwtService;

  @Mock
  private JwtProperties jwtProperties;

  @InjectMocks
  private AuthServiceImpl authService;

  @Test
  void should_issue_token_when_telegram_init_data_is_valid() {
    // Given
    var telegramUser = new TelegramWebAppUser(12345L, "john", "John");
    var user = buildUser(7L, UserRole.USER);
    when(initDataValidator.validate("raw-init-data")).thenReturn(telegramUser);
    when(userService.findOrCreate(12345L, "john", "John")).thenReturn(user);
    when(jwtService.generateToken("7", List.of("USER"))).thenReturn("signed-jwt");
    when(jwtProperties.accessTokenExpiry()).thenReturn(3600L);

    // When
    var result = authService.authenticateWithTelegram("raw-init-data");

    // Then
    assertThat(result.accessToken()).isEqualTo("signed-jwt");
    assertThat(result.expiresIn()).isEqualTo(3600L);
  }

  @Test
  void should_pass_admin_role_when_user_is_admin() {
    // Given
    var telegramUser = new TelegramWebAppUser(99L, "admin", "Admin");
    var user = buildUser(1L, UserRole.ADMIN);
    when(initDataValidator.validate("raw-init-data")).thenReturn(telegramUser);
    when(userService.findOrCreate(99L, "admin", "Admin")).thenReturn(user);
    when(jwtService.generateToken(eq("1"), eq(List.of("ADMIN")))).thenReturn("admin-jwt");
    when(jwtProperties.accessTokenExpiry()).thenReturn(3600L);

    // When
    var result = authService.authenticateWithTelegram("raw-init-data");

    // Then
    assertThat(result.accessToken()).isEqualTo("admin-jwt");
  }

  @Test
  void should_propagate_exception_when_init_data_is_invalid() {
    // Given
    when(initDataValidator.validate("bad-init-data"))
        .thenThrow(new InvalidTelegramAuthException("initData signature is invalid"));

    // When / Then
    assertThatThrownBy(() -> authService.authenticateWithTelegram("bad-init-data"))
        .isInstanceOf(InvalidTelegramAuthException.class)
        .hasMessageContaining("signature");
  }

  // -------------------------------------------------------------------------
  // authenticateWithTelegramLoginWidget
  // -------------------------------------------------------------------------

  @Test
  void should_issue_token_when_login_widget_user_is_admin() {
    // Given
    var request = buildWidgetRequest(99L);
    var user = buildUser(1L, UserRole.ADMIN);
    when(userService.findByTelegramId(99L)).thenReturn(Optional.of(user));
    when(jwtService.generateToken(eq("1"), eq(List.of("ADMIN")))).thenReturn("admin-jwt");
    when(jwtProperties.accessTokenExpiry()).thenReturn(3600L);

    // When
    var result = authService.authenticateWithTelegramLoginWidget(request);

    // Then
    assertThat(result.accessToken()).isEqualTo("admin-jwt");
    verify(loginWidgetValidator).validate(request);
  }

  @Test
  void should_deny_access_when_login_widget_user_is_not_admin() {
    // Given
    var request = buildWidgetRequest(99L);
    var user = buildUser(1L, UserRole.USER);
    when(userService.findByTelegramId(99L)).thenReturn(Optional.of(user));

    // When / Then
    assertThatThrownBy(() -> authService.authenticateWithTelegramLoginWidget(request))
        .isInstanceOf(AdminAccessDeniedException.class);
    verify(jwtService, never()).generateToken(any(), any());
  }

  @Test
  void should_deny_access_when_login_widget_user_is_unknown() {
    // Given — must not create a new user through this flow
    var request = buildWidgetRequest(404L);
    when(userService.findByTelegramId(404L)).thenReturn(Optional.empty());

    // When / Then
    assertThatThrownBy(() -> authService.authenticateWithTelegramLoginWidget(request))
        .isInstanceOf(AdminAccessDeniedException.class);
    verify(userService, never()).findOrCreate(any(), any(), any());
    verify(jwtService, never()).generateToken(any(), any());
  }

  @Test
  void should_propagate_exception_when_login_widget_payload_is_invalid() {
    // Given
    var request = buildWidgetRequest(99L);
    doThrow(new InvalidTelegramAuthException("Telegram Login Widget signature is invalid"))
        .when(loginWidgetValidator).validate(request);

    // When / Then
    assertThatThrownBy(() -> authService.authenticateWithTelegramLoginWidget(request))
        .isInstanceOf(InvalidTelegramAuthException.class)
        .hasMessageContaining("signature");
    verify(userService, never()).findByTelegramId(any());
  }

  private static TelegramLoginWidgetRequest buildWidgetRequest(Long telegramId) {
    return new TelegramLoginWidgetRequest(
        telegramId, "John", null, "johndoe", null, 1_700_000_000L, "signed-hash");
  }

  private static User buildUser(Long id, UserRole role) {
    var user = new User();
    user.setId(id);
    user.setRole(role);
    return user;
  }
}
