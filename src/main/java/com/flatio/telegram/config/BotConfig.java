package com.flatio.telegram.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the Telegram bot.
 *
 * <p>Values are read from environment variables {@code TELEGRAM_BOT_TOKEN} and
 * {@code TELEGRAM_BOT_USERNAME}. Both are required — startup fails if either is missing.
 *
 * <p>{@code webhookUrl} ({@code TELEGRAM_WEBHOOK_URL}) is only required outside the
 * {@code local} profile, where the bot uses webhook delivery instead of long-polling.
 * It is not validated here because the {@code local} profile never needs it.
 */
@ConfigurationProperties(prefix = "telegram.bot")
public record BotConfig(String token, String username, String webhookUrl) {

  public BotConfig {
    if (token == null || token.isBlank()) {
      throw new IllegalStateException(
          "Telegram bot token is not configured. Set the TELEGRAM_BOT_TOKEN environment variable.");
    }
    if (username == null || username.isBlank()) {
      throw new IllegalStateException(
          "Telegram bot username is not configured. Set the TELEGRAM_BOT_USERNAME environment variable.");
    }
  }
}
