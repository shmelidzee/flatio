package com.flatio.security;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Telegram user identity embedded in the {@code user} field of WebApp {@code initData}.
 *
 * <p>Only the fields needed to identify the user are mapped; Telegram sends additional
 * fields (language_code, photo_url, etc.) which are ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TelegramWebAppUser(
    Long id,
    String username,
    @JsonProperty("first_name") String firstName
) {}
