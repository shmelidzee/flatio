package com.flatio.service;

import com.flatio.web.dto.AuthResponse;
import com.flatio.web.dto.TelegramLoginWidgetRequest;

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

  /**
   * Validates a Telegram Login Widget payload and issues a JWT access token for the admin panel.
   *
   * <p>Unlike {@link #authenticateWithTelegram}, this never creates a new user — the Telegram
   * identity must already belong to a registered {@code ADMIN} user, otherwise access is denied.
   *
   * @param request the widget callback payload, never null
   * @return issued access token and its validity period, never null
   * @throws com.flatio.common.exception.InvalidTelegramAuthException if the payload fails
   *     signature or freshness validation
   * @throws com.flatio.common.exception.AdminAccessDeniedException if the Telegram user is
   *     unknown or is not an {@code ADMIN}
   */
  AuthResponse authenticateWithTelegramLoginWidget(TelegramLoginWidgetRequest request);

  /**
   * Returns the public Telegram bot username needed to render the Telegram Login Widget.
   *
   * <p>Exposed via the API (rather than baked into the admin SPA at build time as a
   * {@code VITE_}-prefixed env var) because the SPA is built once into a Docker image and
   * that image is reused across environments — a build-time value could not vary per
   * deployment the way this runtime config lookup can, and it keeps the bot username defined
   * in exactly one place ({@link com.flatio.telegram.config.BotConfig}).
   *
   * @return the configured bot username, never null
   */
  String getTelegramBotUsername();
}
