package com.flatio.telegram.handler;

import com.flatio.telegram.callback.FilterCallbackHandler;
import com.flatio.telegram.command.StartCommandHandler;
import com.flatio.telegram.config.BotConfig;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;
import org.telegram.telegrambots.longpolling.starter.SpringLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

/**
 * Main Telegram bot bean for Flatio.
 *
 * <p>Implements {@link SpringLongPollingBot} so the
 * {@code telegrambots-springboot-longpolling-starter} auto-configuration discovers and registers
 * it automatically. Implements {@link LongPollingUpdateConsumer} to process incoming updates
 * in the same class.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class FlatioBot implements SpringLongPollingBot, LongPollingUpdateConsumer {

  private static final String FILTER_CALLBACK_PREFIX = "FILTER:";

  private final BotConfig botConfig;
  private final TelegramClient telegramClient;
  private final StartCommandHandler startCommandHandler;
  private final FilterCallbackHandler filterCallbackHandler;

  /**
   * Returns the bot API token used for long-polling authentication.
   *
   * @return bot token from {@code TELEGRAM_BOT_TOKEN}, never null
   */
  @Override
  public String getBotToken() {
    return botConfig.token();
  }

  /**
   * Returns this instance as the update consumer; all updates are handled in-place.
   *
   * @return this bot instance, never null
   */
  @Override
  public LongPollingUpdateConsumer getUpdatesConsumer() {
    return this;
  }

  /**
   * Processes a batch of incoming Telegram updates.
   *
   * @param updates list of updates received from Telegram, never null
   */
  @Override
  public void consume(List<Update> updates) {
    updates.forEach(this::handleUpdate);
  }

  private void handleUpdate(Update update) {
    log.debug("Update received: updateId={}", update.getUpdateId());
    if (update.hasMessage() && update.getMessage().hasText()) {
      handleTextMessage(update);
    } else if (update.hasCallbackQuery()) {
      handleCallbackQuery(update.getCallbackQuery());
    }
  }

  private void handleTextMessage(Update update) {
    String text = update.getMessage().getText();
    if (text.startsWith("/start")) {
      try {
        telegramClient.execute(startCommandHandler.handle(update));
      } catch (TelegramApiException e) {
        log.error("Failed to send /start reply: chatId={}", update.getMessage().getChatId(), e);
      } catch (Exception e) {
        log.error("Unexpected error handling /start: chatId={}, updateId={}",
            update.getMessage().getChatId(), update.getUpdateId(), e);
      }
    }
  }

  private void handleCallbackQuery(CallbackQuery callbackQuery) {
    String data = callbackQuery.getData();
    answerCallbackQuery(callbackQuery.getId());

    if ("action:search".equals(data) || data.startsWith(FILTER_CALLBACK_PREFIX)) {
      try {
        telegramClient.execute(filterCallbackHandler.handle(callbackQuery));
      } catch (TelegramApiException e) {
        log.error("Failed to edit filter wizard message: chatId={}",
            callbackQuery.getMessage().getChatId(), e);
      } catch (Exception e) {
        log.error("Unexpected error handling filter callback: data={}", data, e);
      }
    }
  }

  private void answerCallbackQuery(String callbackQueryId) {
    try {
      telegramClient.execute(AnswerCallbackQuery.builder()
          .callbackQueryId(callbackQueryId)
          .build());
    } catch (TelegramApiException e) {
      log.warn("Failed to answer callback query: id={}", callbackQueryId, e);
    }
  }
}
