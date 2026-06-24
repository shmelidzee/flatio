package com.flatio.telegram.config;

import com.flatio.telegram.handler.FlatioBot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.telegram.telegrambots.meta.api.methods.updates.SetWebhook;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import org.telegram.telegrambots.webhook.starter.SpringTelegramWebhookBot;

/**
 * Webhook delivery transport for {@link FlatioBot}, active in every profile except
 * {@code local} (where the bot uses long-polling instead — see {@code FlatioLongPollingBot}).
 *
 * <p>Registers a {@link SpringTelegramWebhookBot} bean; the
 * {@code telegrambots-springboot-webhook-starter} auto-configuration picks it up and calls
 * Telegram's {@code setWebhook} automatically at startup.
 *
 * <p>The webhook path is the bot token itself — Telegram's own recommended technique for
 * keeping the endpoint unguessable, since the starter does not support a secret-header check.
 *
 * <p>No {@code deleteWebhook} callback is wired: {@code TelegramBotsSpringWebhookApplication}
 * (the starter's own bean) implements {@code AutoCloseable}, so Spring calls it on every
 * context shutdown — including the old instance during a rolling deploy (Railway, k8s, etc.).
 * If we deleted the webhook there, the old instance's shutdown could race past the new
 * instance's startup and erase the webhook the new instance just registered, leaving the bot
 * unreachable until the next restart. Re-registering on every startup is idempotent and
 * sufficient; nothing needs to run on shutdown.
 */
@Configuration
@Profile("!local")
@Slf4j
@RequiredArgsConstructor
public class TelegramWebhookConfig {

  private final BotConfig botConfig;
  private final TelegramClient telegramClient;
  private final FlatioBot flatioBot;

  /**
   * Builds the webhook bot bean consumed by the webhook starter at startup.
   *
   * @return webhook bot wired to {@link FlatioBot}, never null
   * @throws IllegalStateException if {@code TELEGRAM_WEBHOOK_URL} is not configured
   */
  @Bean
  public SpringTelegramWebhookBot flatioWebhookBot() {
    String webhookUrl = requireWebhookUrl();
    String botPath = botConfig.token();
    return SpringTelegramWebhookBot.builder()
        .botPath(botPath)
        .updateHandler(update -> {
          flatioBot.handleUpdateAsync(update);
          return null;
        })
        .setWebhook(() -> registerWebhook(webhookUrl, botPath))
        .build();
  }

  private String requireWebhookUrl() {
    String webhookUrl = botConfig.webhookUrl();
    if (webhookUrl == null || webhookUrl.isBlank()) {
      throw new IllegalStateException(
          "Telegram webhook URL is not configured. Set the TELEGRAM_WEBHOOK_URL environment variable.");
    }
    // Strip a trailing slash so a misconfigured TELEGRAM_WEBHOOK_URL never produces a double slash.
    return webhookUrl.endsWith("/") ? webhookUrl.substring(0, webhookUrl.length() - 1) : webhookUrl;
  }

  private void registerWebhook(String webhookUrl, String botPath) {
    try {
      SetWebhook.SetWebhookBuilder builder = SetWebhook.builder()
          .url(webhookUrl + "/" + botPath);
      String secretToken = botConfig.webhookSecretToken();
      if (secretToken != null && !secretToken.isBlank()) {
        builder.secretToken(secretToken);
      }
      telegramClient.execute(builder.build());
      log.info("Telegram webhook registered successfully");
    } catch (TelegramApiException e) {
      log.error("Failed to register Telegram webhook", e);
    }
  }
}
