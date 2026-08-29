package com.flatio.telegram.handler;

import com.flatio.telegram.callback.BlacklistCallbackHandler;
import com.flatio.telegram.callback.FavoritesCallbackHandler;
import com.flatio.telegram.callback.FilterCallbackHandler;
import com.flatio.telegram.callback.SubscriptionsCallbackHandler;
import com.flatio.telegram.command.BlacklistCommandHandler;
import com.flatio.telegram.command.FavoritesCommandHandler;
import com.flatio.telegram.command.HelpCommandHandler;
import com.flatio.telegram.command.SearchCommandHandler;
import com.flatio.telegram.command.StartCommandHandler;
import com.flatio.telegram.command.SubscriptionsCommandHandler;
import com.flatio.telegram.state.SearchFilterWizard;
import java.util.Arrays;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

/**
 * Transport-agnostic Telegram update router for Flatio.
 *
 * <p>Holds all Update-processing logic regardless of how updates are delivered. The actual
 * delivery transport — long-polling or webhook — is registered by a separate bean
 * ({@code FlatioLongPollingBot} for the {@code local} profile, {@code TelegramWebhookConfig}
 * otherwise) which forwards every received {@link Update} to {@link #handleUpdateAsync(Update)}.
 *
 * <p>Routing rules:
 * <ul>
 *   <li>{@code /start} — {@link StartCommandHandler}</li>
 *   <li>{@code /search} — {@link SearchCommandHandler}: shows last-search choice if filter exists, otherwise starts wizard</li>
 *   <li>{@code /favorites} — {@link FavoritesCommandHandler} (issue #473)</li>
 *   <li>{@code /subscriptions} — {@link SubscriptionsCommandHandler} (issue #473)</li>
 *   <li>{@code /blacklist} — {@link BlacklistCommandHandler} (issue #473)</li>
 *   <li>{@code /help} — {@link HelpCommandHandler}</li>
 *   <li>Free text while wizard is at KEYWORD step — forwarded to {@link FilterCallbackHandler}</li>
 *   <li>{@code action:search}, {@code FILTER:*} callbacks — {@link FilterCallbackHandler}</li>
 *   <li>{@code action:use-last-search} callback — {@link SearchResultSender#handleLastSearch}</li>
 *   <li>{@code action:help} callback — {@link HelpCommandHandler}</li>
 *   <li>{@code FILTER:SEARCH} callback — {@link SearchResultSender}</li>
 *   <li>{@code PAGE:*} callbacks — {@link SearchResultSender}</li>
 *   <li>{@code action:favorites} callback — {@link FavoritesCallbackHandler} (issue #456)</li>
 *   <li>{@code action:subscriptions} callback — {@link SubscriptionsCallbackHandler} (issue #456)</li>
 *   <li>{@code action:blacklist} callback — {@link BlacklistCallbackHandler} (issue #456)</li>
 *   <li>{@code FAV:*} callbacks — {@link FavoritesCallbackHandler} (issue #457)</li>
 *   <li>{@code SUB:*} callbacks — {@link SubscriptionsCallbackHandler} (issue #458)</li>
 *   <li>{@code BL:*} callbacks — {@link BlacklistCallbackHandler} (issue #459)</li>
 *   <li>Free text while a subscription-name or stop-word prompt is pending — forwarded to
 *       {@link SubscriptionsCallbackHandler}/{@link BlacklistCallbackHandler} (issues #458, #459)</li>
 * </ul>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class FlatioBot {

  private static final String FILTER_CALLBACK_PREFIX = SearchFilterWizard.CALLBACK_PREFIX + ":";
  private static final String FILTER_SEARCH_CALLBACK = SearchFilterWizard.CALLBACK_PREFIX + ":SEARCH";
  private static final String ACTION_HELP = "action:help";
  private static final String ACTION_MENU = SearchResultSender.ACTION_MENU;
  private static final String ERROR_TEXT = "Произошла ошибка, попробуйте позже";

  /** Telegram error code returned when the user has blocked the bot. */
  private static final int ERROR_CODE_BLOCKED = 403;

  private final TelegramClient telegramClient;
  private final StartCommandHandler startCommandHandler;
  private final HelpCommandHandler helpCommandHandler;
  private final SearchCommandHandler searchCommandHandler;
  private final FilterCallbackHandler filterCallbackHandler;
  private final SearchResultSender searchResultSender;
  private final FavoritesCallbackHandler favoritesCallbackHandler;
  private final SubscriptionsCallbackHandler subscriptionsCallbackHandler;
  private final BlacklistCallbackHandler blacklistCallbackHandler;
  private final FavoritesCommandHandler favoritesCommandHandler;
  private final SubscriptionsCommandHandler subscriptionsCommandHandler;
  private final BlacklistCommandHandler blacklistCommandHandler;
  private final SearchFilterWizard wizard;
  private final ThreadPoolTaskExecutor telegramUpdateExecutor;

  /**
   * Dispatches a single Telegram update to the executor for concurrent processing.
   *
   * <p>Each update is handled in its own thread so a slow handler
   * (e.g. search with multiple SendPhoto calls) does not block other updates.
   * Exceptions inside {@link #handleUpdate(Update)} are caught there and do not propagate.
   * Called by the transport-specific bean (long-polling or webhook) for every update received.
   *
   * @param update update received from Telegram, never null
   */
  public void handleUpdateAsync(Update update) {
    telegramUpdateExecutor.execute(() -> handleUpdate(update));
  }

  /**
   * Routes a single Telegram update to the matching command, callback, or free-text handler.
   *
   * @param update update received from Telegram, never null
   */
  public void handleUpdate(Update update) {
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
      if (isBlockedByUser(e)) {
        try {
          handleBlockedByUser(Long.valueOf(chatId));
        } catch (NumberFormatException nfe) {
          log.debug("Bot blocked by user (non-numeric chatId, skipping state clear): chatId={}", chatId);
        }
        return;
      }
      log.warn("Failed to send error message to user: chatId={}", chatId, e);
    }
  }

  private void handleTextMessage(Update update) {
    String text = update.getMessage().getText();
    Long userId = update.getMessage().getFrom().getId();
    String chatId = String.valueOf(update.getMessage().getChatId());

    if (dispatchSectionCommand(update, text)) {
      return;
    }
    if (text.startsWith("/start")) {
      try {
        telegramClient.execute(startCommandHandler.handle(update));
      } catch (TelegramApiException e) {
        logOrHandleBlocked(e, userId, "Failed to send /start reply: chatId={}", chatId);
      } catch (Exception e) {
        log.error("Unexpected error handling /start: chatId={}, updateId={}", chatId, update.getUpdateId(), e);
      }
    } else if (text.startsWith("/search")) {
      try {
        telegramClient.execute(searchCommandHandler.handle(userId, chatId));
      } catch (TelegramApiException e) {
        logOrHandleBlocked(e, userId, "Failed to send search wizard: chatId={}", chatId);
      } catch (Exception e) {
        log.error("Unexpected error handling /search: chatId={}, updateId={}", chatId, update.getUpdateId(), e);
      }
    } else if (text.startsWith("/help")) {
      try {
        telegramClient.execute(helpCommandHandler.handle(update));
      } catch (TelegramApiException e) {
        logOrHandleBlocked(e, userId, "Failed to send /help reply: chatId={}", chatId);
      }
    } else {
      handleFreeText(userId, chatId, text);
    }
  }

  /**
   * Routes {@code /favorites}, {@code /subscriptions}, {@code /blacklist} text commands to their
   * section handlers (issue #473) — split out of {@link #handleTextMessage} to keep it within the
   * method-length limit.
   *
   * @param update Telegram update containing the text command, never null
   * @param text   the message text, never null
   * @return true if the command was one of the three section commands and was handled
   */
  private boolean dispatchSectionCommand(Update update, String text) {
    if (text.startsWith("/favorites")) {
      favoritesCommandHandler.handle(update);
    } else if (text.startsWith("/subscriptions")) {
      subscriptionsCommandHandler.handle(update);
    } else if (text.startsWith("/blacklist")) {
      blacklistCommandHandler.handle(update);
    } else {
      return false;
    }
    return true;
  }

  private void handleFreeText(Long userId, String chatId, String text) {
    if (filterCallbackHandler.isAtKeywordStep(userId)) {
      try {
        telegramClient.execute(filterCallbackHandler.handleKeywordText(userId, chatId, text));
      } catch (TelegramApiException e) {
        logOrHandleBlocked(e, userId, "Failed to send DONE step after keyword input: chatId={}", chatId);
      }
    } else if (subscriptionsCallbackHandler.isAwaitingSubscriptionName(userId)) {
      subscriptionsCallbackHandler.handleSubscriptionNameText(userId, chatId, text);
    } else if (blacklistCallbackHandler.isAwaitingKeyword(userId)) {
      blacklistCallbackHandler.handleKeywordText(userId, chatId, text);
    }
  }

  private void handleCallbackQuery(CallbackQuery callbackQuery) {
    String data = callbackQuery.getData();

    // FAV:/SUB:/BL: callbacks answer the callback query themselves, often with a custom toast
    // text (e.g. "Добавлено в избранное") — they must not be pre-answered with empty text below.
    if (data.startsWith(FavoritesCallbackHandler.CALLBACK_PREFIX)) {
      handleFavoritesCallback(callbackQuery, data);
      return;
    }
    if (data.startsWith(SubscriptionsCallbackHandler.CALLBACK_PREFIX)) {
      handleSubscriptionsCallback(callbackQuery, data);
      return;
    }
    if (data.startsWith(BlacklistCallbackHandler.CALLBACK_PREFIX)) {
      handleBlacklistCallback(callbackQuery, data);
      return;
    }

    answerCallbackQuery(callbackQuery.getId());
    dispatchSectionOrSearchCallback(callbackQuery, data);
  }

  /**
   * Dispatches every callback pre-dating the {@code FAV:}/{@code SUB:}/{@code BL:} management
   * callbacks (issues #457–#459): search execution/pagination, help, main menu, opening the three
   * read-only sections (issue #456), and the filter wizard. Split out of
   * {@link #handleCallbackQuery} to keep both methods within the method-length limit.
   *
   * @param callbackQuery the incoming callback query, never null
   * @param data          the callback data, never null
   */
  private void dispatchSectionOrSearchCallback(CallbackQuery callbackQuery, String data) {
    if (FILTER_SEARCH_CALLBACK.equals(data)) {
      searchResultSender.handle(callbackQuery);
    } else if (SearchCommandHandler.ACTION_USE_LAST_SEARCH.equals(data)) {
      searchResultSender.handleLastSearch(callbackQuery);
    } else if (data.startsWith(SearchResultSender.PAGE_CALLBACK_PREFIX)) {
      searchResultSender.handlePageCallback(callbackQuery);
    } else if (ACTION_HELP.equals(data)) {
      try {
        telegramClient.execute(helpCommandHandler.handleCallback(callbackQuery));
      } catch (TelegramApiException e) {
        logOrHandleBlocked(e, callbackQuery.getFrom().getId(),
            "Failed to send help message: chatId={}", callbackQuery.getMessage().getChatId());
      }
    } else if (ACTION_MENU.equals(data)) {
      try {
        String chatId = String.valueOf(callbackQuery.getMessage().getChatId());
        telegramClient.execute(startCommandHandler.buildMenuMessage(chatId));
      } catch (TelegramApiException e) {
        logOrHandleBlocked(e, callbackQuery.getFrom().getId(),
            "Failed to send main menu: chatId={}", callbackQuery.getMessage().getChatId());
      }
    } else if (FavoritesCallbackHandler.ACTION_FAVORITES.equals(data)) {
      favoritesCallbackHandler.handle(callbackQuery);
    } else if (SubscriptionsCallbackHandler.ACTION_SUBSCRIPTIONS.equals(data)) {
      subscriptionsCallbackHandler.handle(callbackQuery);
    } else if (BlacklistCallbackHandler.ACTION_BLACKLIST.equals(data)) {
      blacklistCallbackHandler.handle(callbackQuery);
    } else if (FilterCallbackHandler.ACTION_SEARCH.equals(data) || data.startsWith(FILTER_CALLBACK_PREFIX)) {
      try {
        telegramClient.execute(filterCallbackHandler.handle(callbackQuery));
      } catch (TelegramApiException e) {
        logOrHandleBlocked(e, callbackQuery.getFrom().getId(),
            "Failed to edit filter wizard message: chatId={}", callbackQuery.getMessage().getChatId());
      } catch (Exception e) {
        log.error("Unexpected error handling filter callback: data={}", data, e);
      }
    }
  }

  /**
   * Routes a {@code FAV:*} callback (issue #457) — pagination is self-rendered by the handler,
   * while the add/remove actions answer the callback with a toast built from the handler's result.
   *
   * @param callbackQuery the incoming callback query, never null
   * @param data          the callback data, never null
   */
  private void handleFavoritesCallback(CallbackQuery callbackQuery, String data) {
    if (data.startsWith(FavoritesCallbackHandler.PAGE_PREFIX)) {
      answerCallbackQuery(callbackQuery.getId());
      favoritesCallbackHandler.handlePage(callbackQuery);
    } else if (data.startsWith(FavoritesCallbackHandler.ADD_PREFIX)) {
      answerCallbackQuery(callbackQuery.getId(), favoritesCallbackHandler.handleAdd(callbackQuery));
    } else if (data.startsWith(FavoritesCallbackHandler.REMOVE_PREFIX)) {
      answerCallbackQuery(callbackQuery.getId(), favoritesCallbackHandler.handleRemove(callbackQuery));
    } else {
      answerCallbackQuery(callbackQuery.getId());
    }
  }

  /**
   * Routes a {@code SUB:*} callback (issue #458) — see {@link #handleFavoritesCallback} for the
   * same self-rendering vs. toast split.
   *
   * @param callbackQuery the incoming callback query, never null
   * @param data          the callback data, never null
   */
  private void handleSubscriptionsCallback(CallbackQuery callbackQuery, String data) {
    if (SubscriptionsCallbackHandler.CREATE_FROM_FILTER.equals(data)) {
      answerCallbackQuery(callbackQuery.getId());
      subscriptionsCallbackHandler.handleCreateFromFilter(callbackQuery);
    } else if (SubscriptionsCallbackHandler.START_SEARCH.equals(data)) {
      answerCallbackQuery(callbackQuery.getId());
      subscriptionsCallbackHandler.handleStartSearch(callbackQuery);
    } else if (data.startsWith(SubscriptionsCallbackHandler.PAUSE_PREFIX)) {
      answerCallbackQuery(callbackQuery.getId(), subscriptionsCallbackHandler.handlePause(callbackQuery));
    } else if (data.startsWith(SubscriptionsCallbackHandler.RESUME_PREFIX)) {
      answerCallbackQuery(callbackQuery.getId(), subscriptionsCallbackHandler.handleResume(callbackQuery));
    } else if (data.startsWith(SubscriptionsCallbackHandler.DELETE_PREFIX)) {
      answerCallbackQuery(callbackQuery.getId(), subscriptionsCallbackHandler.handleDelete(callbackQuery));
    } else {
      answerCallbackQuery(callbackQuery.getId());
    }
  }

  /**
   * Routes a {@code BL:*} callback (issue #459) — see {@link #handleFavoritesCallback} for the
   * same self-rendering vs. toast split.
   *
   * @param callbackQuery the incoming callback query, never null
   * @param data          the callback data, never null
   */
  private void handleBlacklistCallback(CallbackQuery callbackQuery, String data) {
    if (BlacklistCallbackHandler.ADD_KEYWORD.equals(data)) {
      answerCallbackQuery(callbackQuery.getId());
      blacklistCallbackHandler.handleAddKeywordPrompt(callbackQuery);
    } else if (data.startsWith(BlacklistCallbackHandler.FILTER_PREFIX)) {
      answerCallbackQuery(callbackQuery.getId());
      blacklistCallbackHandler.handleFilter(callbackQuery);
    } else if (data.startsWith(BlacklistCallbackHandler.DELETE_PREFIX)) {
      answerCallbackQuery(callbackQuery.getId(), blacklistCallbackHandler.handleDelete(callbackQuery));
    } else if (data.startsWith(BlacklistCallbackHandler.HIDE_LISTING_PREFIX)) {
      answerCallbackQuery(callbackQuery.getId(), blacklistCallbackHandler.handleHideListing(callbackQuery));
    } else if (data.startsWith(BlacklistCallbackHandler.HIDE_SOURCE_PREFIX)) {
      answerCallbackQuery(callbackQuery.getId(), blacklistCallbackHandler.handleHideSource(callbackQuery));
    } else {
      answerCallbackQuery(callbackQuery.getId());
    }
  }

  /**
   * Checks whether a {@link TelegramApiException} is Telegram's "bot was blocked by the user"
   * error (HTTP 403), as opposed to a genuine delivery failure.
   *
   * @param e the exception caught while calling the Telegram API
   * @return true if the user has blocked the bot
   */
  private boolean isBlockedByUser(TelegramApiException e) {
    return e instanceof TelegramApiRequestException re
        && Integer.valueOf(ERROR_CODE_BLOCKED).equals(re.getErrorCode());
  }

  /**
   * Clears wizard and search-session state for a user who has blocked the bot (issue #383).
   *
   * <p>Logged at DEBUG, not ERROR/WARN — a user blocking the bot is routine behaviour, not an
   * incident, and treating it as one drowns out genuine delivery failures in monitoring.
   *
   * @param telegramId Telegram user identifier, never null
   */
  private void handleBlockedByUser(Long telegramId) {
    log.debug("Bot blocked by user, clearing wizard/session state: telegramId={}", telegramId);
    wizard.reset(telegramId);
    searchResultSender.clearSession(telegramId);
  }

  /**
   * Routes a failed Telegram API call to blocked-user cleanup (issue #383) or, for any other
   * failure, logs it at ERROR exactly as before.
   *
   * @param e            the exception caught while calling the Telegram API
   * @param telegramId   the user the call was for, never null
   * @param errorFormat  SLF4J-style format string for the non-blocked case
   * @param errorArgs    arguments for {@code errorFormat}; {@code e} is appended automatically
   */
  private void logOrHandleBlocked(TelegramApiException e, Long telegramId, String errorFormat, Object... errorArgs) {
    if (isBlockedByUser(e)) {
      handleBlockedByUser(telegramId);
      return;
    }
    Object[] args = Arrays.copyOf(errorArgs, errorArgs.length + 1);
    args[errorArgs.length] = e;
    log.error(errorFormat, args);
  }

  private void answerCallbackQuery(String callbackQueryId) {
    answerCallbackQuery(callbackQueryId, null);
  }

  /**
   * Answers a callback query, optionally showing the given text as a toast notification
   * (issues #457, #458, #459) — e.g. "Добавлено в избранное" after a favorites/subscription/
   * blacklist action.
   *
   * @param callbackQueryId the callback query to answer, never null
   * @param toastText       text to show as a toast, or null to answer without one
   */
  private void answerCallbackQuery(String callbackQueryId, String toastText) {
    try {
      var builder = AnswerCallbackQuery.builder().callbackQueryId(callbackQueryId);
      if (toastText != null) {
        builder.text(toastText);
      }
      telegramClient.execute(builder.build());
    } catch (TelegramApiException e) {
      log.warn("Failed to answer callback query: id={}", callbackQueryId, e);
    }
  }
}
