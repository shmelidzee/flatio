package com.flatio.telegram.command;

import com.flatio.telegram.callback.BlacklistCallbackHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;

import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BlacklistCommandHandler} (issue #473).
 */
@ExtendWith(MockitoExtension.class)
class BlacklistCommandHandlerTest {

  @Mock
  private BlacklistCallbackHandler blacklistCallbackHandler;

  @InjectMocks
  private BlacklistCommandHandler handler;

  @Test
  void should_delegate_to_callback_handler_when_blacklist_command_received_in_private_chat() {
    // Given
    var update = buildUpdate(1L, 100L, true);

    // When
    handler.handle(update);

    // Then
    verify(blacklistCallbackHandler).handleCommand(1L, "100", true);
  }

  @Test
  void should_pass_private_chat_flag_when_command_sent_outside_private_chat() {
    // Given
    var update = buildUpdate(2L, 200L, false);

    // When
    handler.handle(update);

    // Then
    verify(blacklistCallbackHandler).handleCommand(2L, "200", false);
  }

  private Update buildUpdate(Long telegramId, Long chatId, boolean isPrivateChat) {
    var from = mock(User.class);
    lenient().when(from.getId()).thenReturn(telegramId);

    var chat = mock(Chat.class);
    lenient().when(chat.isUserChat()).thenReturn(isPrivateChat);

    var message = mock(Message.class);
    lenient().when(message.getFrom()).thenReturn(from);
    lenient().when(message.getChatId()).thenReturn(chatId);
    lenient().when(message.getChat()).thenReturn(chat);

    var update = mock(Update.class);
    lenient().when(update.getMessage()).thenReturn(message);
    return update;
  }
}
