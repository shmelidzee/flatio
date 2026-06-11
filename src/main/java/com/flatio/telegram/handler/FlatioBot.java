package com.flatio.telegram.handler;

import com.flatio.telegram.callback.FilterCallbackHandler;
import com.flatio.telegram.command.HelpCommandHandler;
import com.flatio.telegram.command.StartCommandHandler;
import com.flatio.telegram.config.BotConfig;
import com.flatio.telegram.state.SearchFilterWizard;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;
import org.telegram.telegrambots.longpolling.starter.SpringLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
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
 *
 * <p>Routing rules:
 * <ul>
 *   <li>{@code /start} — {@link StartCommandHandler}</li>
 *   <li>{@code /search} — starts the filter wizard via {@link FilterCallbackHandler}</li>
 *   <li>{@code /help} — {@link HelpCommandHandler}</li>
 *   <li>Free text while wizard is at KEYWORD step — forwarded to {@link FilterCallbackHandler}</li>
 *   <li>{@code action:search}, {@code FILTER:*} callbacks — {@link FilterCallbackHandler}</li>
 *   <li>{@code action:help} callback — {@link HelpCommandHandler}</li>
 *   <li>{@code FILTER:SEARCH} callback — {@link SearchResultSender}</li>
 *   <li>{@code PAGE:*} callbacks — {@link SearchResultSender}</li>
 * </ul>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class FlatioBot implements SpringLongPollingBot, LongPollingUpdateConsumer {

  private static final String FILTER_CALLBACK_PREFIX = SearchFilterWizard.CALLBACK_PREFIX + ":";
  private static final String FILTER_SEARCH_CALLBACK = SearchFilterWizard.CALLBACK_PREFIX + ":SEARCH";
  private static final String ACTION_HELP = "action:help";
  private static final String ERROR_TEXT = "Произошла ошибка, попробуйте позже";

  private final BotConfig botConfig;
  private final TelegramClient telegramClient;
  private final StartCommandHandler startCommandHandler;
  private final HelpCommandHandler helpCommandHandler;
  private final FilterCallbackHandler filterCallbackHandler;
  private final SearchResultSender searchResultSender;

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
    try {
      if (update.hasMessage() && update.getMessage().hasText()) {
        handleTextMessage(update);
      } else if (update.hasCallbackQuery()) {
        handleCallbackQuery(update.getCallbackQuery());
      }
    } catch (Exception e) {
      String chatId = extractChatId(update);
      log.error("Unhandled exception processing update: updateId={}, chatId={}", update.getUpdateId(), chatId, e);
      if (chatId != null) {
        sendErrorMessage(chatId);
      }
    }
  }

  private String extractChatId(Update update) {
    if (update.hasMessage() && update.getMessage() != null) {
      return String.valueOf(update.getMessage().getChatId());
    }
    if (update.hasCallbackQuery() && update.getCallbackQuery().getMessage() != null) {
      return String.valueOf(update.getCallbackQuery().getMessage().getChatId());
    }
    return null;
  }

  private void sendErrorMessage(String chatId) {
    try {
      telegramClient.execute(SendMessage.builder()
          .chatId(chatId)
          .text(ERROR_TEXT)
          .build());
    } catch (TelegramApiException e) {
      log.warn("Failed to send error message to user: chatId={}", chatId, e);
    }
  }

  private void handleTextMessage(Update update) {
    String text = update.getMessage().getText();
    Long userId = update.getMessage().getFrom().getId();
    String chatId = String.valueOf(update.getMessage().getChatId());

    if (text.startsWith("/start")) {
      try {
        telegramClient.execute(startCommandHandler.handle(update));
      } catch (TelegramApiException e) {
        log.error("Failed to send /start reply: chatId={}", chatId, e);
      } catch (Exception e) {
        log.error("Unexpected error handling /start: chatId={}, updateId={}", chatId, update.getUpdateId(), e);
      }
    } else if (text.startsWith("/search")) {
      try {
        telegramClient.execute(filterCallbackHandler.startWizardMessage(userId, chatId));
      } catch (TelegramApiException e) {
        log.error("Failed to send search wizard: chatId={}", chatId, e);
      }
    } else if (text.startsWith("/help")) {
      try {
        telegramClient.execute(helpCommandHandler.handle(update));
      } catch (TelegramApiException e) {
        log.error("Failed to send /help reply: chatId={}", chatId, e);
      }
    } else {
      handleFreeText(userId, chatId, text);
    }
  }

  private void handleFreeText(Long userId, String chatId, String text) {
    if (!filterCallbackHandler.isAtKeywordStep(userId)) {
      return;
    }
    try {
      telegramClient.execute(filterCallbackHandler.handleKeywordText(userId, chatId, text));
    } catch (TelegramApiException e) {
      log.error("Failed to send DONE step after keyword input: chatId={}", chatId, e);
    }
  }

  private void handleCallbackQuery(CallbackQuery callbackQuery) {
    String data = callbackQuery.getData();
    answerCallbackQuery(callbackQuery.getId());

    if (FILTER_SEARCH_CALLBACK.equals(data)) {
      searchResultSender.handle(callbackQuery);
    } else if (data.startsWith(SearchResultSender.PAGE_CALLBACK_PREFIX)) {
      searchResultSender.handlePageCallback(callbackQuery);
    } else if (ACTION_HELP.equals(data)) {
      try {
        telegramClient.execute(helpCommandHandler.handleCallback(callbackQuery));
      } catch (TelegramApiException e) {
        log.error("Failed to send help message: chatId={}", callbackQuery.getMessage().getChatId(), e);
      }
    } else if (FilterCallbackHandler.ACTION_SEARCH.equals(data) || data.startsWith(FILTER_CALLBACK_PREFIX)) {
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
