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
 *
 * <p>{@code webhookSecretToken} ({@code TELEGRAM_WEBHOOK_SECRET_TOKEN}) is a second-layer
 * security token passed to Telegram's {@code setWebhook} API. Telegram includes it in every
 * update via the {@code X-Telegram-Bot-Api-Secret-Token} header, and the application validates
 * it — so a leaked URL token alone is not enough to forge an update. Not validated here (the
 * {@code local} profile uses long-polling and never needs it), but required outside {@code local}
 * — see {@code TelegramWebhookConfig#requireWebhookSecretToken}.
 */
@ConfigurationProperties(prefix = "telegram.bot")
public record BotConfig(String token, String username, String webhookUrl, String webhookSecretToken) {

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
