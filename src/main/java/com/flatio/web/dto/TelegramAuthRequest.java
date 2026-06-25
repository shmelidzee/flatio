package com.flatio.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

@Schema(description = "Telegram WebApp authentication request")
public record TelegramAuthRequest(
    @Schema(
        description = "Raw initData string provided by the Telegram WebApp via window.Telegram.WebApp.initData",
        example = "query_id=AAH...&user=%7B%22id%22%3A123%7D&auth_date=1700000000&hash=abc123",
        requiredMode = REQUIRED
    )
    @NotBlank
    String initData
) {}
