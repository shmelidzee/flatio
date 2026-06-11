package com.flatio.telegram.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.updates.GetWebhookInfo;
import org.telegram.telegrambots.meta.api.objects.WebhookInfo;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

/**
 * Validates bot token and detects webhook conflicts at application startup.
 *
 * <p>Runs before long-polling begins (during Spring context refresh). If the token is
 * invalid the application fails to start with a clear message. If a webhook is configured,
 * a WARN is logged with instructions on how to remove it — long-polling cannot receive
 * updates while a webhook is active.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class TelegramStartupValidator {

  private final TelegramClient telegramClient;

  /**
   * Checks token validity and detects webhook conflicts.
   *
   * @throws IllegalStateException if the bot token is invalid or expired
   */
  @PostConstruct
  public void validate() {
    log.debug("Validating Telegram bot configuration at startup");
    try {
      WebhookInfo webhookInfo = telegramClient.execute(new GetWebhookInfo());
      String webhookUrl = webhookInfo.getUrl();
      if (webhookUrl != null && !webhookUrl.isBlank()) {
        log.warn(
            "Active Telegram webhook detected: url={}. Long-polling will NOT receive updates while a "
                + "webhook is set. Remove it with: "
                + "curl https://api.telegram.org/bot<TOKEN>/deleteWebhook",
            webhookUrl);
      } else {
        log.debug("Telegram bot configuration validated: no webhook conflict detected");
      }
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
}
