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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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

import static org.assertj.core.api.Assertions.assertThat;
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
    when(startCommandHandler.buildMenuMessage("100", "Pavel")).thenReturn(menuMessage);
    var bot = buildBot(telegramClient, startCommandHandler, mock(SearchResultSender.class), executor);
    var update = buildCallbackUpdate(1, 100L, "action:menu");
    when(update.getCallbackQuery().getFrom().getFirstName()).thenReturn("Pavel");

    // When
    bot.handleUpdate(update);

    // Then
    verify(startCommandHandler).buildMenuMessage("100", "Pavel");
    verify(telegramClient).execute(menuMessage);
  }

  // -------------------------------------------------------------------------
  // Text command routing (issue #473)
  // -------------------------------------------------------------------------

  @Test
  void should_delegate_to_favorites_command_handler_when_favorites_command_received() {
    // Given
    var favoritesCommandHandler = mock(FavoritesCommandHandler.class);
    var bot = buildBotWithFavoritesCommandHandler(favoritesCommandHandler, executor);
    var update = buildTextUpdate(1, 777L, "/favorites");

    // When
    bot.handleUpdate(update);

    // Then
    verify(favoritesCommandHandler).handle(update);
  }

  @Test
  void should_delegate_to_subscriptions_command_handler_when_subscriptions_command_received() {
    // Given
    var subscriptionsCommandHandler = mock(SubscriptionsCommandHandler.class);
    var bot = buildBotWithSubscriptionsCommandHandler(subscriptionsCommandHandler, executor);
    var update = buildTextUpdate(1, 777L, "/subscriptions");

    // When
    bot.handleUpdate(update);

    // Then
    verify(subscriptionsCommandHandler).handle(update);
  }

  @Test
  void should_delegate_to_blacklist_command_handler_when_blacklist_command_received() {
    // Given
    var blacklistCommandHandler = mock(BlacklistCommandHandler.class);
    var bot = buildBotWithBlacklistCommandHandler(blacklistCommandHandler, executor);
    var update = buildTextUpdate(1, 777L, "/blacklist");

    // When
    bot.handleUpdate(update);

    // Then
    verify(blacklistCommandHandler).handle(update);
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
  void should_delegate_to_blacklist_page_handler_when_bl_page_callback_received() {
    // Given — issue #477: blacklist pagination
    var telegramClient = mock(TelegramClient.class);
    var blacklistCallbackHandler = mock(BlacklistCallbackHandler.class);
    var bot = buildBotWithBlacklistHandler(telegramClient, blacklistCallbackHandler, executor);
    var update = buildCallbackUpdate(1, 100L, "BL:PAGE:NEXT");

    // When
    bot.handleUpdate(update);

    // Then
    verify(blacklistCallbackHandler).handlePage(update.getCallbackQuery());
  }

  @Test
  void should_delegate_to_subscriptions_edit_handler_when_sub_edit_callback_received() {
    // Given — issue #479
    var telegramClient = mock(TelegramClient.class);
    var subscriptionsCallbackHandler = mock(SubscriptionsCallbackHandler.class);
    var bot = buildBotWithSubscriptionsHandler(telegramClient, subscriptionsCallbackHandler, executor);
    var update = buildCallbackUpdate(1, 100L, "SUB:EDIT:5");

    // When
    bot.handleUpdate(update);

    // Then
    verify(subscriptionsCallbackHandler).handleEdit(update.getCallbackQuery());
  }

  @Test
  void should_delegate_to_start_search_handler_when_sub_start_search_callback_received() {
    // Given — issue #475
    var telegramClient = mock(TelegramClient.class);
    var subscriptionsCallbackHandler = mock(SubscriptionsCallbackHandler.class);
    var bot = buildBotWithSubscriptionsHandler(telegramClient, subscriptionsCallbackHandler, executor);
    var update = buildCallbackUpdate(1, 100L, "SUB:START_SEARCH");

    // When
    bot.handleUpdate(update);

    // Then
    verify(subscriptionsCallbackHandler).handleStartSearch(update.getCallbackQuery());
  }

  @Test
  void should_save_subscription_edit_when_filter_search_callback_received_during_edit() {
    // Given — issue #479: the wizard is editing subscription #5 for this user
    var telegramClient = mock(TelegramClient.class);
    var searchResultSender = mock(SearchResultSender.class);
    var subscriptionsCallbackHandler = mock(SubscriptionsCallbackHandler.class);
    var wizard = mock(SearchFilterWizard.class);
    var editingState = new com.flatio.telegram.state.SearchFilterState();
    editingState.setEditingSubscriptionId(5L);
    when(wizard.getState(100L)).thenReturn(java.util.Optional.of(editingState));
    var bot = new FlatioBot(
        telegramClient, mock(StartCommandHandler.class), mock(HelpCommandHandler.class),
        mock(SearchCommandHandler.class), mock(FilterCallbackHandler.class),
        searchResultSender, mock(FavoritesCallbackHandler.class),
        subscriptionsCallbackHandler, mock(BlacklistCallbackHandler.class),
        mock(FavoritesCommandHandler.class), mock(SubscriptionsCommandHandler.class), mock(BlacklistCommandHandler.class),
        wizard, executor
    );
    var update = buildCallbackUpdate(1, 100L, "FILTER:SEARCH");

    // When
    bot.handleUpdate(update);

    // Then — routed to saving the subscription, not listing search
    verify(subscriptionsCallbackHandler).handleSaveEdit(update.getCallbackQuery());
    verify(searchResultSender, never()).handle(any());
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
  // Free-text routing for an active-but-button-only wizard step (issue #520)
  // -------------------------------------------------------------------------

  @Test
  void should_reply_with_hint_when_free_text_arrives_at_button_only_wizard_step() throws TelegramApiException {
    // Given — wizard active (e.g. at "Тип сделки"), no keyword/name/stop-word prompt pending
    var telegramClient = mock(TelegramClient.class);
    var filterCallbackHandler = mock(FilterCallbackHandler.class);
    when(filterCallbackHandler.isAtKeywordStep(777L)).thenReturn(false);
    when(filterCallbackHandler.isWizardActive(777L)).thenReturn(true);
    var hintMessage = mock(SendMessage.class);
    when(filterCallbackHandler.handleInvalidFreeText(777L, "777")).thenReturn(hintMessage);
    var bot = buildBotWithFilterHandler(telegramClient, filterCallbackHandler, executor);
    var update = buildTextUpdate(1, 777L, "asdf123");

    // When
    bot.handleUpdate(update);

    // Then
    verify(filterCallbackHandler).handleInvalidFreeText(777L, "777");
    verify(telegramClient).execute(hintMessage);
  }

  @Test
  void should_ignore_free_text_when_no_wizard_and_no_pending_prompt() {
    // Given — no wizard started, no prompt pending: unchanged pre-#520 behaviour (silently ignored)
    var filterCallbackHandler = mock(FilterCallbackHandler.class);
    when(filterCallbackHandler.isAtKeywordStep(777L)).thenReturn(false);
    when(filterCallbackHandler.isWizardActive(777L)).thenReturn(false);
    var bot = buildBotWithFilterHandler(mock(TelegramClient.class), filterCallbackHandler, executor);
    var update = buildTextUpdate(1, 777L, "random message");

    // When
    bot.handleUpdate(update);

    // Then
    verify(filterCallbackHandler, never()).handleInvalidFreeText(any(), any());
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
        mock(FavoritesCommandHandler.class), mock(SubscriptionsCommandHandler.class), mock(BlacklistCommandHandler.class),
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
        mock(FavoritesCommandHandler.class), mock(SubscriptionsCommandHandler.class), mock(BlacklistCommandHandler.class),
        wizard, executor
    );
    var update = buildTextUpdate(1, 778L, "/start");

    // When
    bot.handleUpdate(update);

    // Then — not classified as a block, so wizard/session state is left untouched
    verify(wizard, never()).reset(any());
    verify(searchResultSender, never()).clearSession(any());
  }

  // -------------------------------------------------------------------------
  // Per-user update ordering (issue #518)
  // -------------------------------------------------------------------------

  @Test
  void should_process_updates_from_same_user_strictly_in_order() throws InterruptedException {
    // Given — a slow first update and a fast second update from the SAME Telegram user; the pool
    // has spare threads, so the two would race if updates were not chained per user
    var telegramClient = mock(TelegramClient.class);
    var helpCommandHandler = mock(HelpCommandHandler.class);
    var bot = buildBotWithHelpHandler(telegramClient, helpCommandHandler, executor);

    var firstEntered = new CountDownLatch(1);
    var releaseFirst = new CountDownLatch(1);
    var secondCompleted = new CountDownLatch(1);

    var firstUpdate = buildTextUpdate(1, 555L, "/help");
    var secondUpdate = buildTextUpdate(2, 555L, "/help");

    when(helpCommandHandler.handle(firstUpdate)).thenAnswer(invocation -> {
      firstEntered.countDown();
      assertThat(releaseFirst.await(2, TimeUnit.SECONDS)).isTrue();
      return mock(SendMessage.class);
    });
    when(helpCommandHandler.handle(secondUpdate)).thenAnswer(invocation -> {
      secondCompleted.countDown();
      return mock(SendMessage.class);
    });

    // When
    bot.handleUpdateAsync(firstUpdate);
    assertThat(firstEntered.await(1, TimeUnit.SECONDS)).isTrue();
    bot.handleUpdateAsync(secondUpdate);

    // Then — second update must not run while the first is still blocked
    assertThat(secondCompleted.await(300, TimeUnit.MILLISECONDS)).isFalse();

    // And — once the first is released, the chained second update runs to completion
    releaseFirst.countDown();
    assertThat(secondCompleted.await(2, TimeUnit.SECONDS)).isTrue();
  }

  @Test
  void should_process_updates_from_different_users_without_waiting_on_each_other() throws InterruptedException {
    // Given — two different users; a slow update from user 555 must not delay user 556's update
    var telegramClient = mock(TelegramClient.class);
    var helpCommandHandler = mock(HelpCommandHandler.class);
    var bot = buildBotWithHelpHandler(telegramClient, helpCommandHandler, executor);

    var firstEntered = new CountDownLatch(1);
    var releaseFirst = new CountDownLatch(1);

    var firstUpdate = buildTextUpdate(1, 555L, "/help");
    var otherUserUpdate = buildTextUpdate(2, 556L, "/help");

    when(helpCommandHandler.handle(firstUpdate)).thenAnswer(invocation -> {
      firstEntered.countDown();
      assertThat(releaseFirst.await(2, TimeUnit.SECONDS)).isTrue();
      return mock(SendMessage.class);
    });
    when(helpCommandHandler.handle(otherUserUpdate)).thenReturn(mock(SendMessage.class));

    // When
    bot.handleUpdateAsync(firstUpdate);
    assertThat(firstEntered.await(1, TimeUnit.SECONDS)).isTrue();
    bot.handleUpdateAsync(otherUserUpdate);

    // Then — the other user's update completes without waiting for user 555 to be released
    verify(helpCommandHandler, timeout(ASYNC_TIMEOUT_MS)).handle(otherUserUpdate);

    releaseFirst.countDown();
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
        mock(FavoritesCommandHandler.class),
        mock(SubscriptionsCommandHandler.class),
        mock(BlacklistCommandHandler.class),
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
        mock(FavoritesCommandHandler.class),
        mock(SubscriptionsCommandHandler.class),
        mock(BlacklistCommandHandler.class),
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
        mock(FavoritesCommandHandler.class),
        mock(SubscriptionsCommandHandler.class),
        mock(BlacklistCommandHandler.class),
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
        mock(FavoritesCommandHandler.class),
        mock(SubscriptionsCommandHandler.class),
        mock(BlacklistCommandHandler.class),
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
        mock(FavoritesCommandHandler.class),
        mock(SubscriptionsCommandHandler.class),
        mock(BlacklistCommandHandler.class),
        mock(SearchFilterWizard.class),
        executor
    );
  }

  private static FlatioBot buildBotWithFilterHandler(
      TelegramClient telegramClient, FilterCallbackHandler filterCallbackHandler, ThreadPoolTaskExecutor executor) {
    return new FlatioBot(
        telegramClient,
        mock(StartCommandHandler.class),
        mock(HelpCommandHandler.class),
        mock(SearchCommandHandler.class),
        filterCallbackHandler,
        mock(SearchResultSender.class),
        mock(FavoritesCallbackHandler.class),
        mock(SubscriptionsCallbackHandler.class),
        mock(BlacklistCallbackHandler.class),
        mock(FavoritesCommandHandler.class),
        mock(SubscriptionsCommandHandler.class),
        mock(BlacklistCommandHandler.class),
        mock(SearchFilterWizard.class),
        executor
    );
  }

  private static FlatioBot buildBotWithHelpHandler(
      TelegramClient telegramClient, HelpCommandHandler helpCommandHandler, ThreadPoolTaskExecutor executor) {
    return new FlatioBot(
        telegramClient,
        mock(StartCommandHandler.class),
        helpCommandHandler,
        mock(SearchCommandHandler.class),
        mock(FilterCallbackHandler.class),
        mock(SearchResultSender.class),
        mock(FavoritesCallbackHandler.class),
        mock(SubscriptionsCallbackHandler.class),
        mock(BlacklistCallbackHandler.class),
        mock(FavoritesCommandHandler.class),
        mock(SubscriptionsCommandHandler.class),
        mock(BlacklistCommandHandler.class),
        mock(SearchFilterWizard.class),
        executor
    );
  }

  private static FlatioBot buildBotWithFavoritesCommandHandler(
      FavoritesCommandHandler favoritesCommandHandler, ThreadPoolTaskExecutor executor) {
    return new FlatioBot(
        mock(TelegramClient.class),
        mock(StartCommandHandler.class),
        mock(HelpCommandHandler.class),
        mock(SearchCommandHandler.class),
        mock(FilterCallbackHandler.class),
        mock(SearchResultSender.class),
        mock(FavoritesCallbackHandler.class),
        mock(SubscriptionsCallbackHandler.class),
        mock(BlacklistCallbackHandler.class),
        favoritesCommandHandler,
        mock(SubscriptionsCommandHandler.class),
        mock(BlacklistCommandHandler.class),
        mock(SearchFilterWizard.class),
        executor
    );
  }

  private static FlatioBot buildBotWithSubscriptionsCommandHandler(
      SubscriptionsCommandHandler subscriptionsCommandHandler, ThreadPoolTaskExecutor executor) {
    return new FlatioBot(
        mock(TelegramClient.class),
        mock(StartCommandHandler.class),
        mock(HelpCommandHandler.class),
        mock(SearchCommandHandler.class),
        mock(FilterCallbackHandler.class),
        mock(SearchResultSender.class),
        mock(FavoritesCallbackHandler.class),
        mock(SubscriptionsCallbackHandler.class),
        mock(BlacklistCallbackHandler.class),
        mock(FavoritesCommandHandler.class),
        subscriptionsCommandHandler,
        mock(BlacklistCommandHandler.class),
        mock(SearchFilterWizard.class),
        executor
    );
  }

  private static FlatioBot buildBotWithBlacklistCommandHandler(
      BlacklistCommandHandler blacklistCommandHandler, ThreadPoolTaskExecutor executor) {
    return new FlatioBot(
        mock(TelegramClient.class),
        mock(StartCommandHandler.class),
        mock(HelpCommandHandler.class),
        mock(SearchCommandHandler.class),
        mock(FilterCallbackHandler.class),
        mock(SearchResultSender.class),
        mock(FavoritesCallbackHandler.class),
        mock(SubscriptionsCallbackHandler.class),
        mock(BlacklistCallbackHandler.class),
        mock(FavoritesCommandHandler.class),
        mock(SubscriptionsCommandHandler.class),
        blacklistCommandHandler,
        mock(SearchFilterWizard.class),
        executor
    );
  }

  private static Update buildCallbackUpdate(int updateId, long chatId, String data) {
    // getFrom() is stubbed (issue #479: FILTER:SEARCH routing now reads the caller's Telegram ID
    // to decide between a plain search and saving a subscription being edited) — chatId doubles as
    // the Telegram user ID here since these tests don't care about the distinction.
    var from = mock(User.class);
    when(from.getId()).thenReturn(chatId);

    var message = mock(Message.class);
    when(message.getChatId()).thenReturn(chatId);

    var callbackQuery = mock(CallbackQuery.class);
    when(callbackQuery.getData()).thenReturn(data);
    when(callbackQuery.getId()).thenReturn(String.valueOf(updateId));
    when(callbackQuery.getMessage()).thenReturn(message);
    when(callbackQuery.getFrom()).thenReturn(from);

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
