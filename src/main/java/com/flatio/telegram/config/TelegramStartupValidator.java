package com.flatio.telegram.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.updates.GetWebhookInfo;
import org.telegram.telegrambots.meta.api.objects.WebhookInfo;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

/**
 * Validates bot token and detects long-polling/webhook conflicts at application startup.
 *
 * <p>Runs during Spring context refresh, before either delivery transport begins. If the
 * token is invalid the application fails to start with a clear message. In the {@code local}
 * profile (long-polling), an active webhook is a conflict — Telegram will not deliver updates
 * via {@code getUpdates} while a webhook is set — so a WARN with removal instructions is
 * logged. Outside {@code local}, an active webhook is the expected state and is not warned about.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class TelegramStartupValidator {

  private final TelegramClient telegramClient;
  private final Environment environment;

  /**
   * Checks token validity and detects long-polling/webhook conflicts.
   *
   * @throws IllegalStateException if the bot token is invalid or expired
   */
  @PostConstruct
  public void validate() {
    log.debug("Validating Telegram bot configuration at startup");
    try {
      WebhookInfo webhookInfo = telegramClient.execute(new GetWebhookInfo());
      warnIfWebhookConflictsWithLongPolling(webhookInfo.getUrl());
    } catch (TelegramApiException e) {
      String message = e.getMessage();
      if (message != null && message.contains("Unauthorized")) {
        throw new IllegalStateException(
            "Telegram bot token is invalid or expired. "
                + "Check the TELEGRAM_BOT_TOKEN environment variable and regenerate the token if needed.",
            e);
      }
      log.warn("Could not verify Telegram webhook status at startup: {}", message);
    }
  }

  private void warnIfWebhookConflictsWithLongPolling(String webhookUrl) {
    if (webhookUrl == null || webhookUrl.isBlank()) {
      log.debug("Telegram bot configuration validated: no webhook conflict detected");
      return;
    }
    if (!environment.matchesProfiles("local")) {
      log.debug("Active Telegram webhook detected — expected outside the local profile");
      return;
    }
    log.warn(
        "Active Telegram webhook detected: url={}. Long-polling will NOT receive updates while a "
            + "webhook is set. Remove it with: "
            + "curl https://api.telegram.org/bot<TOKEN>/deleteWebhook",
        webhookUrl);
  }
}
