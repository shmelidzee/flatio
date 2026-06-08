package com.flatio.bot;

import com.flatio.config.BotConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.objects.Update;

/**
 * Main Telegram bot bean for Flatio.
 *
 * <p>Registered as a Spring component and auto-discovered by
 * {@code telegrambots-spring-boot-starter} for long-polling registration.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class FlatioBot extends TelegramLongPollingBot {

  private final BotConfig botConfig;

  /**
   * Returns the bot username configured via {@code TELEGRAM_BOT_USERNAME}.
   *
   * @return bot username, never null or blank
   */
  @Override
  public String getBotUsername() {
    return botConfig.username();
  }

  /**
   * Returns the bot API token configured via {@code TELEGRAM_BOT_TOKEN}.
   *
   * @return bot token, never null or blank
   */
  @Override
  public String getBotToken() {
    return botConfig.token();
  }

  /**
   * Handles incoming Telegram updates.
   *
   * <p>Command handlers are added in subsequent issues (M1.5.2+).
   *
   * @param update incoming Telegram update, never null
   */
  @Override
  public void onUpdateReceived(Update update) {
    log.debug("Update received: updateId={}", update.getUpdateId());
  }
}
