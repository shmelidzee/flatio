package com.flatio.telegram.callback;

import com.flatio.common.exception.BlacklistKeywordLimitExceededException;
import com.flatio.common.exception.ListingNotFoundException;
import com.flatio.common.exception.SourceNotFoundException;
import com.flatio.domain.blacklist.BlacklistEntryType;
import com.flatio.domain.user.User;
import com.flatio.service.BlacklistService;
import com.flatio.service.UserService;
import com.flatio.telegram.keyboard.MainMenuKeyboardFactory;
import com.flatio.telegram.state.BlacklistKeywordPromptState;
import com.flatio.web.dto.BlacklistEntryResponse;
import com.flatio.web.dto.CreateBlacklistEntryRequest;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BlacklistCallbackHandler} (issue #459).
 */
@ExtendWith(MockitoExtension.class)
class BlacklistCallbackHandlerTest {

  @Mock
  private UserService userService;

  @Mock
  private BlacklistService blacklistService;

  @Mock
  private MainMenuKeyboardFactory keyboardFactory;

  @Mock
  private BlacklistKeywordPromptState keywordPromptState;

  @Mock
  private TelegramClient telegramClient;

  @InjectMocks
  private BlacklistCallbackHandler handler;

  @Test
  void should_send_item_and_navigation_messages_when_user_has_entries() throws Exception {
    // Given
    var user = buildUser(7L);
    when(userService.findByTelegramId(1L)).thenReturn(Optional.of(user));
    var entry = buildBlacklistEntry(3L, BlacklistEntryType.KEYWORD, "новостройка");
    when(blacklistService.findByUser(eq(7L), isNull(), any())).thenReturn(new PageImpl<>(List.of(entry)));
    var callback = buildCallback(1L, 100L, true, BlacklistCallbackHandler.ACTION_BLACKLIST);

    // When
    handler.handle(callback);

    // Then
    var captor = ArgumentCaptor.forClass(SendMessage.class);
    verify(telegramClient, times(2)).execute(captor.capture());
    assertThat(captor.getAllValues().get(0).getText()).contains("Стоп-слово").contains("новостройка");
  }

  @Test
  void should_send_empty_message_when_user_has_no_entries() throws Exception {
    // Given
    var user = buildUser(8L);
    when(userService.findByTelegramId(2L)).thenReturn(Optional.of(user));
    when(blacklistService.findByUser(eq(8L), isNull(), any())).thenReturn(Page.empty());
    var callback = buildCallback(2L, 200L, true, BlacklistCallbackHandler.ACTION_BLACKLIST);

    // When
    handler.handle(callback);

    // Then
    var captor = ArgumentCaptor.forClass(SendMessage.class);
    verify(telegramClient, times(2)).execute(captor.capture());
    assertThat(captor.getAllValues().get(0).getText()).isEqualTo("🚫 Ваш чёрный список пока пуст.");
  }

  @Test
  void should_send_empty_message_when_user_is_not_registered() throws Exception {
    // Given
    when(userService.findByTelegramId(3L)).thenReturn(Optional.empty());
    lenient().when(keyboardFactory.buildBackToMenu()).thenReturn(mock(InlineKeyboardMarkup.class));
    var callback = buildCallback(3L, 300L, true, BlacklistCallbackHandler.ACTION_BLACKLIST);

    // When
    handler.handle(callback);

    // Then
    verify(blacklistService, never()).findByUser(any(), any(), any());
  }

  @Test
  void should_send_private_chat_required_message_when_chat_is_not_private() throws Exception {
    // Given — issue #463
    lenient().when(keyboardFactory.buildBackToMenu()).thenReturn(mock(InlineKeyboardMarkup.class));
    var callback = buildCallback(1L, 100L, false, BlacklistCallbackHandler.ACTION_BLACKLIST);

    // When
    handler.handle(callback);

    // Then
    var captor = ArgumentCaptor.forClass(SendMessage.class);
    verify(telegramClient).execute(captor.capture());
    assertThat(captor.getValue().getText()).contains("личные данные");
    verify(userService, never()).findByTelegramId(any());
  }

  @Test
  void should_render_filtered_list_when_filter_callback_received() {
    // Given
    var user = buildUser(7L);
    when(userService.findByTelegramId(1L)).thenReturn(Optional.of(user));
    when(blacklistService.findByUser(eq(7L), eq(BlacklistEntryType.KEYWORD), any())).thenReturn(Page.empty());
    var callback = buildCallback(1L, 100L, true, "BL:FILTER:KEYWORD");

    // When
    handler.handleFilter(callback);

    // Then
    verify(blacklistService).findByUser(eq(7L), eq(BlacklistEntryType.KEYWORD), any());
  }

  @Test
  void should_prompt_for_keyword_when_add_keyword_callback_received() throws Exception {
    // Given
    var callback = buildCallback(1L, 100L, true, BlacklistCallbackHandler.ADD_KEYWORD);

    // When
    handler.handleAddKeywordPrompt(callback);

    // Then
    verify(keywordPromptState).await(1L);
    var captor = ArgumentCaptor.forClass(SendMessage.class);
    verify(telegramClient).execute(captor.capture());
    assertThat(captor.getValue().getText()).isEqualTo("Введите стоп-слово:");
  }

  @Test
  void should_send_private_chat_required_message_when_add_keyword_from_non_private_chat() throws Exception {
    // Given
    var callback = buildCallback(1L, 100L, false, BlacklistCallbackHandler.ADD_KEYWORD);

    // When
    handler.handleAddKeywordPrompt(callback);

    // Then
    verify(keywordPromptState, never()).await(any());
    var captor = ArgumentCaptor.forClass(SendMessage.class);
    verify(telegramClient).execute(captor.capture());
    assertThat(captor.getValue().getText()).contains("личные данные");
  }

  @Test
  void should_add_keyword_when_valid_text_provided() {
    // Given
    when(userService.findByTelegramId(1L)).thenReturn(Optional.of(buildUser(7L)));

    // When
    handler.handleKeywordText(1L, "100", "новостройка");

    // Then
    verify(blacklistService).create(7L, new CreateBlacklistEntryRequest(BlacklistEntryType.KEYWORD, "новостройка"));
    verify(keywordPromptState).clear(1L);
  }

  @Test
  void should_reprompt_when_keyword_is_blank() {
    // When
    handler.handleKeywordText(1L, "100", "   ");

    // Then
    verify(blacklistService, never()).create(any(), any());
    verify(keywordPromptState, never()).clear(any());
  }

  @Test
  void should_reprompt_when_keyword_exceeds_max_length() {
    // Given — 101 characters, one over the FR-BL-3 limit
    String tooLong = "a".repeat(101);

    // When
    handler.handleKeywordText(1L, "100", tooLong);

    // Then
    verify(blacklistService, never()).create(any(), any());
  }

  @Test
  void should_send_limit_exceeded_message_when_keyword_limit_exceeded() {
    // Given
    when(userService.findByTelegramId(1L)).thenReturn(Optional.of(buildUser(7L)));
    doThrow(new BlacklistKeywordLimitExceededException(20)).when(blacklistService).create(eq(7L), any());

    // When / Then — no exception propagates
    handler.handleKeywordText(1L, "100", "новостройка");
    verify(keywordPromptState).clear(1L);
  }

  @Test
  void should_return_deleted_toast_when_delete_succeeds() {
    // Given
    when(userService.findByTelegramId(1L)).thenReturn(Optional.of(buildUser(7L)));
    var callback = buildCallback(1L, 100L, true, "BL:DELETE:5");

    // When
    var toast = handler.handleDelete(callback);

    // Then
    assertThat(toast).isEqualTo("🗑 Запись удалена из чёрного списка");
    verify(blacklistService).delete(7L, 5L);
  }

  @Test
  void should_return_hidden_listing_toast_when_hide_listing_succeeds() {
    // Given
    when(userService.findByTelegramId(1L)).thenReturn(Optional.of(buildUser(7L)));
    var callback = buildCallback(1L, 100L, true, "BL:HIDE_LISTING:42");

    // When
    var toast = handler.handleHideListing(callback);

    // Then
    assertThat(toast).isEqualTo("🚫 Объявление скрыто");
    verify(blacklistService).create(7L, new CreateBlacklistEntryRequest(BlacklistEntryType.LISTING, "42"));
  }

  @Test
  void should_return_not_found_toast_when_hide_listing_target_missing() {
    // Given
    when(userService.findByTelegramId(1L)).thenReturn(Optional.of(buildUser(7L)));
    doThrow(new ListingNotFoundException(42L)).when(blacklistService).create(eq(7L), any());
    var callback = buildCallback(1L, 100L, true, "BL:HIDE_LISTING:42");

    // When
    var toast = handler.handleHideListing(callback);

    // Then
    assertThat(toast).isEqualTo("Запись не найдена.");
  }

  @Test
  void should_return_hidden_source_toast_when_hide_source_succeeds() {
    // Given
    when(userService.findByTelegramId(1L)).thenReturn(Optional.of(buildUser(7L)));
    var callback = buildCallback(1L, 100L, true, "BL:HIDE_SOURCE:realt");

    // When
    var toast = handler.handleHideSource(callback);

    // Then
    assertThat(toast).isEqualTo("🚫 Источник скрыт");
    verify(blacklistService).create(7L, new CreateBlacklistEntryRequest(BlacklistEntryType.SOURCE, "realt"));
  }

  @Test
  void should_return_not_found_toast_when_hide_source_target_missing() {
    // Given
    when(userService.findByTelegramId(1L)).thenReturn(Optional.of(buildUser(7L)));
    doThrow(new SourceNotFoundException("realt")).when(blacklistService).create(eq(7L), any());
    var callback = buildCallback(1L, 100L, true, "BL:HIDE_SOURCE:realt");

    // When
    var toast = handler.handleHideSource(callback);

    // Then
    assertThat(toast).isEqualTo("Запись не найдена.");
  }

  @Test
  void should_return_private_chat_required_toast_when_delete_from_non_private_chat() {
    // Given
    var callback = buildCallback(1L, 100L, false, "BL:DELETE:5");

    // When
    var toast = handler.handleDelete(callback);

    // Then
    assertThat(toast).contains("личные данные");
    verify(userService, never()).findByTelegramId(any());
  }

  // -------------------------------------------------------------------------
  // helpers
  // -------------------------------------------------------------------------

  private User buildUser(Long id) {
    var user = new User();
    user.setId(id);
    return user;
  }

  private BlacklistEntryResponse buildBlacklistEntry(Long id, BlacklistEntryType type, String value) {
    return new BlacklistEntryResponse(id, type, value, Instant.now());
  }

  private CallbackQuery buildCallback(Long telegramId, Long chatId, boolean isPrivateChat, String data) {
    var from = mock(org.telegram.telegrambots.meta.api.objects.User.class);
    lenient().when(from.getId()).thenReturn(telegramId);

    var chat = mock(Chat.class);
    lenient().when(chat.isUserChat()).thenReturn(isPrivateChat);

    var message = mock(Message.class);
    lenient().when(message.getChatId()).thenReturn(chatId);
    lenient().when(message.getChat()).thenReturn(chat);

    var callback = mock(CallbackQuery.class);
    lenient().when(callback.getFrom()).thenReturn(from);
    lenient().when(callback.getMessage()).thenReturn(message);
    lenient().when(callback.getData()).thenReturn(data);
    return callback;
  }
}
