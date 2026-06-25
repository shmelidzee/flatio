package com.flatio.web.controller;

import com.flatio.service.AuthService;
import com.flatio.web.dto.AuthResponse;
import com.flatio.web.dto.TelegramAuthRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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
}
