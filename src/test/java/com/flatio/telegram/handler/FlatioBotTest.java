package com.flatio.telegram.handler;

import com.flatio.telegram.callback.FilterCallbackHandler;
import com.flatio.telegram.command.HelpCommandHandler;
import com.flatio.telegram.command.SearchCommandHandler;
import com.flatio.telegram.command.StartCommandHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
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

  private static FlatioBot buildBot(
      TelegramClient telegramClient, SearchResultSender searchResultSender, ThreadPoolTaskExecutor executor) {
    return new FlatioBot(
        telegramClient,
        mock(StartCommandHandler.class),
        mock(HelpCommandHandler.class),
        mock(SearchCommandHandler.class),
        mock(FilterCallbackHandler.class),
        searchResultSender,
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
