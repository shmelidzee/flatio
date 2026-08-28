package com.flatio.service.notification;

import com.flatio.common.util.TelegramHtmlEscaper;
import com.flatio.domain.alert.AlertType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

/**
 * Delivers source-health alert/recovery notifications to an ops Telegram chat (issue #419).
 *
 * <p>The target chat is configured via {@code flatio.alerts.telegram.chat-id}
 * (env: {@code FLATIO_ALERTS_TELEGRAM_CHAT_ID}) — unlike the bot token, this has no required
 * default: an environment that has not opted into alerting yet must not fail to start, so a
 * blank value degrades to a logged warning instead of sending anything.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class SourceAlertNotifier {

  private final TelegramClient telegramClient;

  @Value("${flatio.alerts.telegram.chat-id:}")
  private String chatId;

  /**
   * Sends a notification that the given rule started (or is still) matching for a source.
   *
   * @param sourceId  connector source identifier
   * @param alertType which rule triggered
   * @param detail    human-readable detail to include (e.g. the age of the last successful sync)
   */
  public void sendFailureAlert(String sourceId, AlertType alertType, String detail) {
    send("🔴 <b>Проблема с источником " + TelegramHtmlEscaper.escapeHtml(sourceId) + "</b>\n"
        + describeType(alertType) + "\n" + TelegramHtmlEscaper.escapeHtml(detail));
  }

  /**
   * Sends a notification that a previously alerting rule has recovered for a source.
   *
   * @param sourceId  connector source identifier
   * @param alertType which rule recovered
   */
  public void sendRecoveryNotice(String sourceId, AlertType alertType) {
    send("✅ <b>Источник " + TelegramHtmlEscaper.escapeHtml(sourceId) + " восстановлен</b>\n"
        + describeType(alertType));
  }

  private String describeType(AlertType alertType) {
    return switch (alertType) {
      case NO_SUCCESSFUL_SYNC -> "Нет ни одного успешного синка за пороговое время.";
      case HIGH_ERROR_RATE -> "Доля неудачных синков за последние запуски превышает порог.";
    };
  }

  private void send(String text) {
    if (chatId.isBlank()) {
      log.warn("Source alert not sent, flatio.alerts.telegram.chat-id is not configured: text={}", text);
      return;
    }
    try {
      telegramClient.execute(SendMessage.builder()
          .chatId(chatId)
          .text(text)
          .parseMode("HTML")
          .build());
    } catch (TelegramApiException e) {
      log.error("Failed to deliver source alert to chatId={}: error={}", chatId, e.getMessage(), e);
    }
  }
}
