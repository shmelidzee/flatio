package com.flatio.web.controller;

import com.flatio.service.AuthService;
import com.flatio.web.dto.AuthResponse;
import com.flatio.web.dto.TelegramAuthRequest;
import com.flatio.web.dto.TelegramBotConfigResponse;
import com.flatio.web.dto.TelegramLoginWidgetRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for issuing JWT access tokens.
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Auth", description = "Issues JWT access tokens for platform clients")
@RequiredArgsConstructor
public class AuthController {

  private final AuthService authService;

  /**
   * Exchanges Telegram WebApp {@code initData} for a JWT access token.
   *
   * <p>Creates the user on first login, same as the bot's {@code /start} command.
   *
   * @param request request body carrying the raw {@code initData} string
   * @return issued access token and its validity period
   */
  @Operation(
      summary = "Exchange Telegram WebApp initData for a JWT",
      description = "Validates the signature and freshness of Telegram WebApp initData, "
          + "creates the user on first login, and issues a JWT access token."
  )
  @ApiResponse(responseCode = "200", description = "Token issued")
  @ApiResponse(responseCode = "400", description = "Invalid request body")
  @ApiResponse(responseCode = "401", description = "initData signature invalid or expired")
  @PostMapping("/telegram")
  public AuthResponse telegramLogin(@Valid @RequestBody TelegramAuthRequest request) {
    return authService.authenticateWithTelegram(request.initData());
  }

  /**
   * Exchanges a Telegram Login Widget callback payload for a JWT access token.
   *
   * <p>Unlike {@link #telegramLogin}, this never creates a new user — the Telegram identity
   * must already belong to a registered {@code ADMIN} user. Intended for browser-based login
   * to the admin panel, where Telegram WebApp {@code initData} is not available.
   *
   * @param request the widget callback payload
   * @return issued access token and its validity period
   */
  @Operation(
      summary = "Exchange a Telegram Login Widget payload for a JWT",
      description = "Validates the signature and freshness of a Telegram Login Widget callback, "
          + "and issues a JWT access token if the Telegram user is a registered ADMIN. "
          + "Never creates a new user."
  )
  @ApiResponse(responseCode = "200", description = "Token issued")
  @ApiResponse(responseCode = "400", description = "Invalid request body")
  @ApiResponse(responseCode = "401", description = "Widget payload signature invalid or expired")
  @ApiResponse(responseCode = "403", description = "Telegram user is unknown or not an ADMIN")
  @PostMapping("/telegram-login-widget")
  public AuthResponse telegramLoginWidget(@Valid @RequestBody TelegramLoginWidgetRequest request) {
    return authService.authenticateWithTelegramLoginWidget(request);
  }

  /**
   * Returns the public Telegram bot username needed to render the Telegram Login Widget on
   * the admin login page.
   *
   * @return the configured bot username
   */
  @Operation(
      summary = "Get the public Telegram bot username",
      description = "Returns the bot username the admin SPA needs to render the Telegram Login "
          + "Widget. Not a secret — the same value is public on the bot's own Telegram profile."
  )
  @ApiResponse(responseCode = "200", description = "Bot username returned")
  @GetMapping("/telegram-bot-username")
  public TelegramBotConfigResponse telegramBotUsername() {
    return new TelegramBotConfigResponse(authService.getTelegramBotUsername());
  }
}
