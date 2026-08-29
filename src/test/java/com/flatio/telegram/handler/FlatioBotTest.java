package com.flatio.telegram.handler;

import com.flatio.telegram.callback.BlacklistCallbackHandler;
import com.flatio.telegram.callback.FavoritesCallbackHandler;
import com.flatio.telegram.callback.FilterCallbackHandler;
import com.flatio.telegram.callback.SubscriptionsCallbackHandler;
import com.flatio.telegram.command.HelpCommandHandler;
import com.flatio.telegram.command.SearchCommandHandler;
import com.flatio.telegram.command.StartCommandHandler;
import com.flatio.telegram.state.SearchFilterWizard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.ApiResponse;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FlatioBotTest {

  private static final long ASYNC_TIMEOUT_MS = 2_000;

  private ThreadPoolTaskExecutor executor;

  @BeforeEach
  void setUp() {
    executor = buildExecutor();
  }

  @AfterEach
  void tearDown() {
    executor.shutdown();
  }

  @Test
  void should_not_stop_processing_next_update_when_handler_throws_exception() throws TelegramApiException {
    // Given — two FILTER:SEARCH callbacks; handle() throws on first call, succeeds on second
    var telegramClient = mock(TelegramClient.class);
    var searchResultSender = mock(SearchResultSender.class);
    var bot = buildBot(telegramClient, searchResultSender, executor);
    doThrow(new RuntimeException("Handler failure"))
        .doNothing()
        .when(searchResultSender).handle(any());

    var update1 = buildCallbackUpdate(1, 100L, "FILTER:SEARCH");
    var update2 = buildCallbackUpdate(2, 200L, "FILTER:SEARCH");

    // When / Then — exception from first update must not propagate to handleUpdateAsync()
    assertThatNoException().isThrownBy(() -> {
      bot.handleUpdateAsync(update1);
      bot.handleUpdateAsync(update2);
    });

    // Both updates reached the search handler (timeout accounts for async dispatch)
    verify(searchResultSender, timeout(ASYNC_TIMEOUT_MS).times(2)).handle(any());
  }

  @Test
  void should_send_error_message_to_user_when_handler_throws_exception() throws TelegramApiException {
    // Given
    var telegramClient = mock(TelegramClient.class);
    var searchResultSender = mock(SearchResultSender.class);
    var bot = buildBot(telegramClient, searchResultSender, executor);
    doThrow(new RuntimeException("Handler failure")).when(searchResultSender).handle(any());

    var update = buildCallbackUpdate(1, 100L, "FILTER:SEARCH");

    // When
    bot.handleUpdateAsync(update);

    // Then — user receives an error notification (timeout accounts for async dispatch)
    verify(telegramClient, timeout(ASYNC_TIMEOUT_MS)).execute(any(SendMessage.class));
  }

  @Test
  void should_dispatch_updates_concurrently_via_executor() {
    // Given — three updates submitted at once
    var searchResultSender = mock(SearchResultSender.class);
    var bot = buildBot(mock(TelegramClient.class), searchResultSender, executor);
    var updates = java.util.List.of(
        buildCallbackUpdate(1, 100L, "FILTER:SEARCH"),
        buildCallbackUpdate(2, 200L, "FILTER:SEARCH"),
        buildCallbackUpdate(3, 300L, "FILTER:SEARCH")
    );

    // When
    assertThatNoException().isThrownBy(() -> updates.forEach(bot::handleUpdateAsync));

    // Then — all three updates processed without blocking the caller
    verify(searchResultSender, timeout(ASYNC_TIMEOUT_MS).times(3)).handle(any());
  }

  @Test
  void should_process_update_synchronously_when_handle_update_called_directly() {
    // Given
    var searchResultSender = mock(SearchResultSender.class);
    var bot = buildBot(mock(TelegramClient.class), searchResultSender, executor);
    var update = buildCallbackUpdate(1, 100L, "FILTER:SEARCH");

    // When
    bot.handleUpdate(update);

    // Then — no executor hop needed, handler runs on the calling thread immediately
    verify(searchResultSender).handle(any());
  }

  @Test
  void should_send_main_menu_when_action_menu_callback_received() throws TelegramApiException {
    // Given
    var telegramClient = mock(TelegramClient.class);
    var startCommandHandler = mock(StartCommandHandler.class);
    var menuMessage = mock(SendMessage.class);
    when(startCommandHandler.buildMenuMessage("100")).thenReturn(menuMessage);
    var bot = buildBot(telegramClient, startCommandHandler, mock(SearchResultSender.class), executor);
    var update = buildCallbackUpdate(1, 100L, "action:menu");

    // When
    bot.handleUpdate(update);

    // Then
    verify(startCommandHandler).buildMenuMessage("100");
    verify(telegramClient).execute(menuMessage);
  }

  // -------------------------------------------------------------------------
  // Section callback routing (issue #456)
  // -------------------------------------------------------------------------

  @Test
  void should_delegate_to_favorites_handler_when_action_favorites_callback_received() {
    // Given — since #457, the handler self-renders the list (one message per item + navigation);
    // FlatioBot only routes the callback and answers it, it no longer sends a returned message.
    var telegramClient = mock(TelegramClient.class);
    var favoritesCallbackHandler = mock(FavoritesCallbackHandler.class);
    var bot = buildBotWithFavoritesHandler(telegramClient, favoritesCallbackHandler, executor);
    var update = buildCallbackUpdate(1, 100L, "action:favorites");

    // When
    bot.handleUpdate(update);

    // Then
    verify(favoritesCallbackHandler).handle(update.getCallbackQuery());
  }

  @Test
  void should_delegate_to_subscriptions_handler_when_action_subscriptions_callback_received() {
    // Given — since #458, the handler self-renders the list; see the favorites test above.
    var telegramClient = mock(TelegramClient.class);
    var subscriptionsCallbackHandler = mock(SubscriptionsCallbackHandler.class);
    var bot = buildBotWithSubscriptionsHandler(telegramClient, subscriptionsCallbackHandler, executor);
    var update = buildCallbackUpdate(1, 100L, "action:subscriptions");

    // When
    bot.handleUpdate(update);

    // Then
    verify(subscriptionsCallbackHandler).handle(update.getCallbackQuery());
  }

  @Test
  void should_delegate_to_blacklist_handler_when_action_blacklist_callback_received() {
    // Given — since #459, the handler self-renders the list; see the favorites test above.
    var telegramClient = mock(TelegramClient.class);
    var blacklistCallbackHandler = mock(BlacklistCallbackHandler.class);
    var bot = buildBotWithBlacklistHandler(telegramClient, blacklistCallbackHandler, executor);
    var update = buildCallbackUpdate(1, 100L, "action:blacklist");

    // When
    bot.handleUpdate(update);

    // Then
    verify(blacklistCallbackHandler).handle(update.getCallbackQuery());
  }

  // -------------------------------------------------------------------------
  // FAV:/SUB:/BL: management callback routing (issues #457, #458, #459)
  // -------------------------------------------------------------------------

  @Test
  void should_answer_callback_with_toast_when_fav_add_callback_received() throws TelegramApiException {
    // Given
    var telegramClient = mock(TelegramClient.class);
    var favoritesCallbackHandler = mock(FavoritesCallbackHandler.class);
    when(favoritesCallbackHandler.handleAdd(any())).thenReturn("⭐ Добавлено в избранное");
    var bot = buildBotWithFavoritesHandler(telegramClient, favoritesCallbackHandler, executor);
    var update = buildCallbackUpdate(1, 100L, "FAV:ADD:42");

    // When
    bot.handleUpdate(update);

    // Then — the toast text returned by the handler is used to answer the callback query
    verify(favoritesCallbackHandler).handleAdd(update.getCallbackQuery());
    verify(telegramClient).execute(argThat((AnswerCallbackQuery a) ->
        "⭐ Добавлено в избранное".equals(a.getText())));
  }

  @Test
  void should_delegate_to_favorites_page_handler_when_fav_page_callback_received() {
    // Given
    var telegramClient = mock(TelegramClient.class);
    var favoritesCallbackHandler = mock(FavoritesCallbackHandler.class);
    var bot = buildBotWithFavoritesHandler(telegramClient, favoritesCallbackHandler, executor);
    var update = buildCallbackUpdate(1, 100L, "FAV:PAGE:NEXT");

    // When
    bot.handleUpdate(update);

    // Then
    verify(favoritesCallbackHandler).handlePage(update.getCallbackQuery());
  }

  @Test
  void should_delegate_to_subscribe_from_filter_handler_when_sub_create_from_filter_callback_received() {
    // Given
    var telegramClient = mock(TelegramClient.class);
    var subscriptionsCallbackHandler = mock(SubscriptionsCallbackHandler.class);
    var bot = buildBotWithSubscriptionsHandler(telegramClient, subscriptionsCallbackHandler, executor);
    var update = buildCallbackUpdate(1, 100L, "SUB:CREATE_FROM_FILTER");

    // When
    bot.handleUpdate(update);

    // Then
    verify(subscriptionsCallbackHandler).handleCreateFromFilter(update.getCallbackQuery());
  }

  @Test
  void should_delegate_to_subscriptions_page_handler_when_sub_page_callback_received() {
    // Given — issue #478
    var telegramClient = mock(TelegramClient.class);
    var subscriptionsCallbackHandler = mock(SubscriptionsCallbackHandler.class);
    var bot = buildBotWithSubscriptionsHandler(telegramClient, subscriptionsCallbackHandler, executor);
    var update = buildCallbackUpdate(1, 100L, "SUB:PAGE:NEXT");

    // When
    bot.handleUpdate(update);

    // Then
    verify(subscriptionsCallbackHandler).handlePage(update.getCallbackQuery());
  }

  @Test
  void should_answer_callback_with_toast_when_bl_delete_callback_received() throws TelegramApiException {
    // Given
    var telegramClient = mock(TelegramClient.class);
    var blacklistCallbackHandler = mock(BlacklistCallbackHandler.class);
    when(blacklistCallbackHandler.handleDelete(any())).thenReturn("🗑 Запись удалена из чёрного списка");
    var bot = buildBotWithBlacklistHandler(telegramClient, blacklistCallbackHandler, executor);
    var update = buildCallbackUpdate(1, 100L, "BL:DELETE:7");

    // When
    bot.handleUpdate(update);

    // Then
    verify(blacklistCallbackHandler).handleDelete(update.getCallbackQuery());
    verify(telegramClient).execute(argThat((AnswerCallbackQuery a) ->
        "🗑 Запись удалена из чёрного списка".equals(a.getText())));
  }

  // -------------------------------------------------------------------------
  // Free-text routing for subscription-name / stop-word prompts (issues #458, #459)
  // -------------------------------------------------------------------------

  @Test
  void should_forward_free_text_to_subscriptions_handler_when_awaiting_subscription_name() {
    // Given
    var subscriptionsCallbackHandler = mock(SubscriptionsCallbackHandler.class);
    when(subscriptionsCallbackHandler.isAwaitingSubscriptionName(777L)).thenReturn(true);
    var bot = buildBotWithSubscriptionsHandler(mock(TelegramClient.class), subscriptionsCallbackHandler, executor);
    var update = buildTextUpdate(1, 777L, "2-комнатные в центре");

    // When
    bot.handleUpdate(update);

    // Then
    verify(subscriptionsCallbackHandler).handleSubscriptionNameText(777L, "777", "2-комнатные в центре");
  }

  @Test
  void should_forward_free_text_to_blacklist_handler_when_awaiting_keyword() {
    // Given
    var blacklistCallbackHandler = mock(BlacklistCallbackHandler.class);
    when(blacklistCallbackHandler.isAwaitingKeyword(777L)).thenReturn(true);
    var bot = buildBotWithBlacklistHandler(mock(TelegramClient.class), blacklistCallbackHandler, executor);
    var update = buildTextUpdate(1, 777L, "новостройка");

    // When
    bot.handleUpdate(update);

    // Then
    verify(blacklistCallbackHandler).handleKeywordText(777L, "777", "новостройка");
  }

  // -------------------------------------------------------------------------
  // Blocked-by-user handling (issue #383)
  // -------------------------------------------------------------------------

  @Test
  void should_clear_wizard_and_session_when_user_blocked_bot_during_start() throws TelegramApiException {
    // Given
    var telegramClient = mock(TelegramClient.class);
    var startCommandHandler = mock(StartCommandHandler.class);
    var searchResultSender = mock(SearchResultSender.class);
    var wizard = mock(SearchFilterWizard.class);
    when(startCommandHandler.handle(any())).thenReturn(mock(SendMessage.class));
    doThrow(buildBlockedException()).when(telegramClient).execute(any(SendMessage.class));
    var bot = new FlatioBot(
        telegramClient, startCommandHandler, mock(HelpCommandHandler.class),
        mock(SearchCommandHandler.class), mock(FilterCallbackHandler.class),
        searchResultSender, mock(FavoritesCallbackHandler.class),
        mock(SubscriptionsCallbackHandler.class), mock(BlacklistCallbackHandler.class),
        wizard, executor
    );
    var update = buildTextUpdate(1, 777L, "/start");

    // When
    bot.handleUpdate(update);

    // Then — state cleared for the blocked user instead of an error notification attempt
    verify(wizard).reset(777L);
    verify(searchResultSender).clearSession(777L);
  }

  @Test
  void should_send_error_notification_when_send_fails_for_reason_other_than_blocked() throws TelegramApiException {
    // Given — a non-403 failure (e.g. network error) must not be treated as a block
    var telegramClient = mock(TelegramClient.class);
    var startCommandHandler = mock(StartCommandHandler.class);
    var searchResultSender = mock(SearchResultSender.class);
    var wizard = mock(SearchFilterWizard.class);
    when(startCommandHandler.handle(any())).thenReturn(mock(SendMessage.class));
    doThrow(new TelegramApiException("network error")).when(telegramClient).execute(any(SendMessage.class));
    var bot = new FlatioBot(
        telegramClient, startCommandHandler, mock(HelpCommandHandler.class),
        mock(SearchCommandHandler.class), mock(FilterCallbackHandler.class),
        searchResultSender, mock(FavoritesCallbackHandler.class),
        mock(SubscriptionsCallbackHandler.class), mock(BlacklistCallbackHandler.class),
        wizard, executor
    );
    var update = buildTextUpdate(1, 778L, "/start");

    // When
    bot.handleUpdate(update);

    // Then — not classified as a block, so wizard/session state is left untouched
    verify(wizard, never()).reset(any());
    verify(searchResultSender, never()).clearSession(any());
  }

  private static TelegramApiRequestException buildBlockedException() {
    var response = ApiResponse.builder()
        .ok(false)
        .errorCode(403)
        .errorDescription("Forbidden: bot was blocked by the user")
        .build();
    return new TelegramApiRequestException("Error", response);
  }

  private static Update buildTextUpdate(int updateId, long userId, String text) {
    var user = mock(User.class);
    when(user.getId()).thenReturn(userId);

    var message = mock(Message.class);
    when(message.hasText()).thenReturn(true);
    when(message.getText()).thenReturn(text);
    when(message.getFrom()).thenReturn(user);
    when(message.getChatId()).thenReturn(userId);

    var update = mock(Update.class);
    when(update.getUpdateId()).thenReturn(updateId);
    when(update.hasMessage()).thenReturn(true);
    when(update.getMessage()).thenReturn(message);
    return update;
  }

  private static FlatioBot buildBot(
      TelegramClient telegramClient, SearchResultSender searchResultSender, ThreadPoolTaskExecutor executor) {
    return new FlatioBot(
        telegramClient,
        mock(StartCommandHandler.class),
        mock(HelpCommandHandler.class),
        mock(SearchCommandHandler.class),
        mock(FilterCallbackHandler.class),
        searchResultSender,
        mock(FavoritesCallbackHandler.class),
        mock(SubscriptionsCallbackHandler.class),
        mock(BlacklistCallbackHandler.class),
        mock(SearchFilterWizard.class),
        executor
    );
  }

  private static FlatioBot buildBot(
      TelegramClient telegramClient, StartCommandHandler startCommandHandler,
      SearchResultSender searchResultSender, ThreadPoolTaskExecutor executor) {
    return new FlatioBot(
        telegramClient,
        startCommandHandler,
        mock(HelpCommandHandler.class),
        mock(SearchCommandHandler.class),
        mock(FilterCallbackHandler.class),
        searchResultSender,
        mock(FavoritesCallbackHandler.class),
        mock(SubscriptionsCallbackHandler.class),
        mock(BlacklistCallbackHandler.class),
        mock(SearchFilterWizard.class),
        executor
    );
  }

  private static FlatioBot buildBotWithFavoritesHandler(
      TelegramClient telegramClient, FavoritesCallbackHandler favoritesCallbackHandler, ThreadPoolTaskExecutor executor) {
    return new FlatioBot(
        telegramClient,
        mock(StartCommandHandler.class),
        mock(HelpCommandHandler.class),
        mock(SearchCommandHandler.class),
        mock(FilterCallbackHandler.class),
        mock(SearchResultSender.class),
        favoritesCallbackHandler,
        mock(SubscriptionsCallbackHandler.class),
        mock(BlacklistCallbackHandler.class),
        mock(SearchFilterWizard.class),
        executor
    );
  }

  private static FlatioBot buildBotWithSubscriptionsHandler(
      TelegramClient telegramClient, SubscriptionsCallbackHandler subscriptionsCallbackHandler,
      ThreadPoolTaskExecutor executor) {
    return new FlatioBot(
        telegramClient,
        mock(StartCommandHandler.class),
        mock(HelpCommandHandler.class),
        mock(SearchCommandHandler.class),
        mock(FilterCallbackHandler.class),
        mock(SearchResultSender.class),
        mock(FavoritesCallbackHandler.class),
        subscriptionsCallbackHandler,
        mock(BlacklistCallbackHandler.class),
        mock(SearchFilterWizard.class),
        executor
    );
  }

  private static FlatioBot buildBotWithBlacklistHandler(
      TelegramClient telegramClient, BlacklistCallbackHandler blacklistCallbackHandler, ThreadPoolTaskExecutor executor) {
    return new FlatioBot(
        telegramClient,
        mock(StartCommandHandler.class),
        mock(HelpCommandHandler.class),
        mock(SearchCommandHandler.class),
        mock(FilterCallbackHandler.class),
        mock(SearchResultSender.class),
        mock(FavoritesCallbackHandler.class),
        mock(SubscriptionsCallbackHandler.class),
        blacklistCallbackHandler,
        mock(SearchFilterWizard.class),
        executor
    );
  }

  private static Update buildCallbackUpdate(int updateId, long chatId, String data) {
    var message = mock(Message.class);
    when(message.getChatId()).thenReturn(chatId);

    var callbackQuery = mock(CallbackQuery.class);
    when(callbackQuery.getData()).thenReturn(data);
    when(callbackQuery.getId()).thenReturn(String.valueOf(updateId));
    when(callbackQuery.getMessage()).thenReturn(message);

    var update = mock(Update.class);
    when(update.getUpdateId()).thenReturn(updateId);
    when(update.hasCallbackQuery()).thenReturn(true);
    when(update.getCallbackQuery()).thenReturn(callbackQuery);
    return update;
  }

  private static ThreadPoolTaskExecutor buildExecutor() {
    var executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(5);
    executor.setMaxPoolSize(10);
    executor.setQueueCapacity(100);
    executor.initialize();
    return executor;
  }
}
