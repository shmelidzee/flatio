package com.flatio.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

@Schema(description = "Public Telegram bot configuration needed to render the Login Widget")
public record TelegramBotConfigResponse(
    @Schema(description = "Public Telegram bot username", example = "flatio_bot", requiredMode = REQUIRED)
    String botUsername
) {}
