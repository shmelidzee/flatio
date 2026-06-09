package com.flatio.telegram.command;

import com.flatio.service.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StartCommandHandlerTest {

  @Mock
  private UserService userService;

  @InjectMocks
  private StartCommandHandler handler;

  @Test
  void should_call_find_or_create_with_telegram_user_data() {
    // Given
    var update = buildUpdate(100L, "alice", "Alice", 42L);
    when(userService.findOrCreate(anyLong(), any(), any()))
        .thenReturn(new com.flatio.domain.user.User());

    // When
    handler.handle(update);

    // Then
    verify(userService).findOrCreate(100L, "alice", "Alice");
  }

  @Test
  void should_return_send_message_with_correct_chat_id() {
    // Given
    var update = buildUpdate(200L, "bob", "Bob", 99L);
    when(userService.findOrCreate(anyLong(), any(), any()))
        .thenReturn(new com.flatio.domain.user.User());

    // When
    SendMessage result = handler.handle(update);

    // Then
    assertThat(result).isNotNull();
    assertThat(result.getChatId()).isEqualTo("99");
  }

  @Test
  void should_include_first_name_in_greeting_when_present() {
    // Given
    var update = buildUpdate(300L, "carol", "Carol", 55L);
    when(userService.findOrCreate(anyLong(), any(), any()))
        .thenReturn(new com.flatio.domain.user.User());

    // When
    SendMessage result = handler.handle(update);

    // Then
    assertThat(result.getText()).contains("Carol");
  }

  @Test
  void should_use_generic_greeting_when_first_name_is_null() {
    // Given
    var update = buildUpdate(400L, "dave", null, 77L);
    when(userService.findOrCreate(anyLong(), any(), any()))
        .thenReturn(new com.flatio.domain.user.User());

    // When
    SendMessage result = handler.handle(update);

    // Then
    assertThat(result.getText()).startsWith("Привет!");
  }

  @Test
  void should_include_reply_markup_with_two_buttons() {
    // Given
    var update = buildUpdate(500L, "eve", "Eve", 88L);
    when(userService.findOrCreate(anyLong(), any(), any()))
        .thenReturn(new com.flatio.domain.user.User());

    // When
    SendMessage result = handler.handle(update);

    // Then
    assertThat(result.getReplyMarkup()).isNotNull();
    var keyboard = (InlineKeyboardMarkup) result.getReplyMarkup();
    assertThat(keyboard.getKeyboard()).hasSize(1);
    assertThat(keyboard.getKeyboard().get(0)).hasSize(2);
  }

  @Test
  void should_set_search_and_help_callback_data_on_buttons() {
    // Given
    var update = buildUpdate(600L, "frank", "Frank", 11L);
    when(userService.findOrCreate(anyLong(), any(), any()))
        .thenReturn(new com.flatio.domain.user.User());

    // When
    SendMessage result = handler.handle(update);

    // Then
    var keyboard = (InlineKeyboardMarkup) result.getReplyMarkup();
    var row = keyboard.getKeyboard().get(0);
    assertThat(row.get(0).getCallbackData()).isEqualTo("action:search");
    assertThat(row.get(1).getCallbackData()).isEqualTo("action:help");
  }

  // -------------------------------------------------------------------------
  // helpers
  // -------------------------------------------------------------------------

  private Update buildUpdate(long fromId, String username, String firstName, long chatId) {
    var from = mock(org.telegram.telegrambots.meta.api.objects.User.class);
    when(from.getId()).thenReturn(fromId);
    when(from.getUserName()).thenReturn(username);
    when(from.getFirstName()).thenReturn(firstName);

    var message = mock(Message.class);
    when(message.getFrom()).thenReturn(from);
    when(message.getChatId()).thenReturn(chatId);

    var update = mock(Update.class);
    when(update.getMessage()).thenReturn(message);
    return update;
  }
}
