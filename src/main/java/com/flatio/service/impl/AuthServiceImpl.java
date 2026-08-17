package com.flatio.service.impl;

import com.flatio.common.exception.AdminAccessDeniedException;
import com.flatio.domain.user.UserRole;
import com.flatio.security.JwtProperties;
import com.flatio.security.JwtService;
import com.flatio.security.TelegramInitDataValidator;
import com.flatio.security.TelegramLoginWidgetValidator;
import com.flatio.service.AuthService;
import com.flatio.service.UserService;
import com.flatio.telegram.config.BotConfig;
import com.flatio.web.dto.AuthResponse;
import com.flatio.web.dto.TelegramLoginWidgetRequest;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementation of {@link AuthService} backed by Telegram identity.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

  private final TelegramInitDataValidator initDataValidator;
  private final TelegramLoginWidgetValidator loginWidgetValidator;
  private final UserService userService;
  private final JwtService jwtService;
  private final JwtProperties jwtProperties;
  private final BotConfig botConfig;

  @Override
  @Transactional
  public AuthResponse authenticateWithTelegram(String initData) {
    var telegramUser = initDataValidator.validate(initData);
    var user = userService.findOrCreate(telegramUser.id(), telegramUser.username(), telegramUser.firstName());
    return issueToken(user.getId(), user.getRole());
  }

  @Override
  public AuthResponse authenticateWithTelegramLoginWidget(TelegramLoginWidgetRequest request) {
    loginWidgetValidator.validate(request);
    var user = userService.findByTelegramId(request.id())
        .filter(candidate -> candidate.getRole() == UserRole.ADMIN)
        .orElseThrow(() -> new AdminAccessDeniedException(
            "This Telegram account does not have admin-panel access"));
    return issueToken(user.getId(), user.getRole());
  }

  @Override
  public String getTelegramBotUsername() {
    return botConfig.username();
  }

  private AuthResponse issueToken(Long userId, UserRole role) {
    var accessToken = jwtService.generateToken(userId.toString(), List.of(role.name()));
    return new AuthResponse(accessToken, jwtProperties.accessTokenExpiry());
  }
}
