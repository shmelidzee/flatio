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
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
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
 *   <li>{@code SUB:*} callbacks — {@link SubscriptionsCallbackHandler} (issues #458, #479)</li>
 *   <li>{@code FILTER:SEARCH} when the wizard is editing a subscription (issue #479) —
 *       {@link SubscriptionsCallbackHandler#handleSaveEdit} instead of {@link SearchResultSender}</li>
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

  private static final long UPDATE_CHAIN_TTL_MINUTES = 5;
  private static final long MAX_UPDATE_CHAINS = 50_000;

  // A per-user chain of CompletableFutures (issue #518): two updates from the same Telegram user
  // submitted close together (e.g. a "SUB:DELETE:" callback immediately followed by a
  // "/subscriptions" text command) would otherwise land on two different telegramUpdateExecutor
  // threads with no ordering guarantee, so the read could run — and reply — before the delete's
  // transaction committed, making the just-deleted item appear to "come back" in the list.
  // Chaining each user's updates off their own previous CompletableFuture keeps that one user's
  // updates strictly sequential while different users still run fully in parallel on the shared
  // pool. Caffeine, not a plain map, so a user who stops messaging does not pin an entry forever.
  private final Map<Long, CompletableFuture<Void>> updateChains = Caffeine.newBuilder()
      .expireAfterAccess(Duration.ofMinutes(UPDATE_CHAIN_TTL_MINUTES))
      .maximumSize(MAX_UPDATE_CHAINS)
      .<Long, CompletableFuture<Void>>build()
      .asMap();

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
   * <p>Each update is handled on the shared pool so a slow handler (e.g. search with multiple
   * SendPhoto calls) does not block other users' updates. Updates from the same Telegram user,
   * however, are chained to run strictly in submission order (issue #518) — see
   * {@link #updateChains}; updates the router cannot attribute to a user (no {@code from} on the
   * message/callback) run immediately on the pool with no ordering applied.
   * Exceptions inside {@link #handleUpdate(Update)} are caught there and do not propagate.
   * Called by the transport-specific bean (long-polling or webhook) for every update received.
   *
   * @param update update received from Telegram, never null
   */
  public void handleUpdateAsync(Update update) {
    Long telegramId = extractTelegramUserId(update);
    if (telegramId == null) {
      telegramUpdateExecutor.execute(() -> handleUpdate(update));
      return;
    }
    updateChains.compute(telegramId, (id, previousTail) -> {
      CompletableFuture<Void> previous = previousTail != null ? previousTail : CompletableFuture.completedFuture(null);
      // handleAsync (not thenRunAsync) runs regardless of how the previous stage completed, and
      // always completes this stage normally itself — thenRunAsync would instead have skipped
      // this update and propagated the earlier failure forever, permanently stalling every later
      // update from this user, if a previous handleUpdate ever let a Throwable escape its own
      // try/catch (that catch only covers Exception, not Error).
      return previous.handleAsync((ignoredResult, ignoredError) -> runUpdateSafely(update), telegramUpdateExecutor);
    });
  }

  /**
   * Runs {@link #handleUpdate(Update)}, guaranteeing normal completion of its
   * {@link #updateChains} stage even if something unexpected escapes it, so one bad update never
   * permanently stalls that user's later updates.
   *
   * @param update the update to process, never null
   */
  private Void runUpdateSafely(Update update) {
    try {
      handleUpdate(update);
    } catch (Throwable t) {
      log.error("Throwable escaped handleUpdate; continuing to process this user's later updates: updateId={}",
          update.getUpdateId(), t);
    }
    return null;
  }

  /**
   * Resolves the Telegram user identifier an update should be sequenced by, for
   * {@link #updateChains}.
   *
   * @param update the incoming update, never null
   * @return the sending user's Telegram ID, or null if the update carries none
   */
  private Long extractTelegramUserId(Update update) {
    if (update.hasMessage() && update.getMessage().getFrom() != null) {
      return update.getMessage().getFrom().getId();
    }
    if (update.hasCallbackQuery() && update.getCallbackQuery().getFrom() != null) {
      return update.getCallbackQuery().getFrom().getId();
    }
    return null;
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
    } else if (filterCallbackHandler.isWizardActive(userId)) {
      // Wizard is active but at a button-only step (issue #520) — every other pending-input
      // state above is checked first since it takes priority over an in-progress search wizard.
      try {
        telegramClient.execute(filterCallbackHandler.handleInvalidFreeText(userId, chatId));
      } catch (TelegramApiException e) {
        logOrHandleBlocked(e, userId, "Failed to send invalid-input hint: chatId={}", chatId);
      }
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
      dispatchFilterSearchCallback(callbackQuery);
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
        telegramClient.execute(startCommandHandler.buildMenuMessage(chatId, callbackQuery.getFrom().getFirstName()));
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
   * Routes the {@code FILTER:SEARCH} callback (the wizard's DONE-step primary button) to listing
   * search execution, or — when the wizard is editing an existing subscription's criteria
   * (issue #479) — to saving that subscription instead.
   *
   * @param callbackQuery the incoming callback query, never null
   */
  private void dispatchFilterSearchCallback(CallbackQuery callbackQuery) {
    Long telegramId = callbackQuery.getFrom().getId();
    boolean isEditingSubscription = wizard.getState(telegramId)
        .map(state -> state.getEditingSubscriptionId() != null)
        .orElse(false);
    if (isEditingSubscription) {
      subscriptionsCallbackHandler.handleSaveEdit(callbackQuery);
    } else {
      searchResultSender.handle(callbackQuery);
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
    } else if (data.startsWith(SubscriptionsCallbackHandler.PAGE_PREFIX)) {
      answerCallbackQuery(callbackQuery.getId());
      subscriptionsCallbackHandler.handlePage(callbackQuery);
    } else if (SubscriptionsCallbackHandler.START_SEARCH.equals(data)) {
      answerCallbackQuery(callbackQuery.getId());
      subscriptionsCallbackHandler.handleStartSearch(callbackQuery);
    } else if (data.startsWith(SubscriptionsCallbackHandler.PAUSE_PREFIX)) {
      answerCallbackQuery(callbackQuery.getId(), subscriptionsCallbackHandler.handlePause(callbackQuery));
    } else if (data.startsWith(SubscriptionsCallbackHandler.RESUME_PREFIX)) {
      answerCallbackQuery(callbackQuery.getId(), subscriptionsCallbackHandler.handleResume(callbackQuery));
    } else if (data.startsWith(SubscriptionsCallbackHandler.DELETE_PREFIX)) {
      answerCallbackQuery(callbackQuery.getId(), subscriptionsCallbackHandler.handleDelete(callbackQuery));
    } else if (data.startsWith(SubscriptionsCallbackHandler.EDIT_PREFIX)) {
      answerCallbackQuery(callbackQuery.getId());
      subscriptionsCallbackHandler.handleEdit(callbackQuery);
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
    if (data.startsWith(BlacklistCallbackHandler.PAGE_PREFIX)) {
      answerCallbackQuery(callbackQuery.getId());
      blacklistCallbackHandler.handlePage(callbackQuery);
    } else if (BlacklistCallbackHandler.ADD_KEYWORD.equals(data)) {
      answerCallbackQuery(callbackQuery.getId());
      blacklistCallbackHandler.handleAddKeywordPrompt(callbackQuery);
    } else if (BlacklistCallbackHandler.CANCEL_KEYWORD.equals(data)) {
      answerCallbackQuery(callbackQuery.getId());
      blacklistCallbackHandler.handleCancelKeyword(callbackQuery);
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
