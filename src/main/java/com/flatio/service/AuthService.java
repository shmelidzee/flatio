package com.flatio.service;

import com.flatio.web.dto.AuthResponse;

/**
 * Service for issuing access tokens to platform users.
 */
public interface AuthService {

  /**
   * Validates Telegram WebApp {@code initData}, finds or creates the corresponding user,
   * and issues a JWT access token for them.
   *
   * @param initData raw Telegram WebApp {@code initData} string, never null
   * @return issued access token and its validity period, never null
   * @throws com.flatio.common.exception.InvalidTelegramAuthException if {@code initData}
   *     fails signature or freshness validation
   */
  AuthResponse authenticateWithTelegram(String initData);
}
