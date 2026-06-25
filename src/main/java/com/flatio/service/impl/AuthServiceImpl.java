package com.flatio.service.impl;

import com.flatio.security.JwtProperties;
import com.flatio.security.JwtService;
import com.flatio.security.TelegramInitDataValidator;
import com.flatio.service.AuthService;
import com.flatio.service.UserService;
import com.flatio.web.dto.AuthResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementation of {@link AuthService} backed by Telegram WebApp identity.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

  private final TelegramInitDataValidator initDataValidator;
  private final UserService userService;
  private final JwtService jwtService;
  private final JwtProperties jwtProperties;

  @Override
  @Transactional
  public AuthResponse authenticateWithTelegram(String initData) {
    var telegramUser = initDataValidator.validate(initData);
    var user = userService.findOrCreate(telegramUser.id(), telegramUser.username(), telegramUser.firstName());
    var accessToken = jwtService.generateToken(user.getId().toString(), List.of(user.getRole().name()));
    return new AuthResponse(accessToken, jwtProperties.accessTokenExpiry());
  }
}
