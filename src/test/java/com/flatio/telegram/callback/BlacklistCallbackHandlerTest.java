package com.flatio.telegram.callback;

import com.flatio.domain.blacklist.BlacklistEntryType;
import com.flatio.domain.user.User;
import com.flatio.service.BlacklistService;
import com.flatio.service.UserService;
import com.flatio.telegram.keyboard.MainMenuKeyboardFactory;
import com.flatio.web.dto.BlacklistEntryResponse;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BlacklistCallbackHandler} (issue #456).
 */
@ExtendWith(MockitoExtension.class)
class BlacklistCallbackHandlerTest {

  @Mock
  private UserService userService;

  @Mock
  private BlacklistService blacklistService;

  @Mock
  private MainMenuKeyboardFactory keyboardFactory;

  @InjectMocks
  private BlacklistCallbackHandler handler;

  @Test
  void should_return_formatted_blacklist_when_user_has_entries() {
    // Given
    var user = buildUser(7L);
    when(userService.findByTelegramId(1L)).thenReturn(Optional.of(user));
    var entry = buildBlacklistEntry(3L, BlacklistEntryType.KEYWORD, "новостройка");
    when(blacklistService.findByUser(eq(7L), isNull(), any())).thenReturn(new PageImpl<>(List.of(entry)));
    lenient().when(keyboardFactory.buildBackToMenu()).thenReturn(mock(InlineKeyboardMarkup.class));
    var callback = buildCallback(1L, 100L);

    // When
    var result = handler.handle(callback);

    // Then
    assertThat(result.getText()).contains("Чёрный список");
    assertThat(result.getText()).contains("Стоп-слово");
    assertThat(result.getText()).contains("новостройка");
  }

  @Test
  void should_return_empty_message_when_user_has_no_blacklist_entries() {
    // Given
    var user = buildUser(8L);
    when(userService.findByTelegramId(2L)).thenReturn(Optional.of(user));
    when(blacklistService.findByUser(eq(8L), isNull(), any())).thenReturn(Page.empty());
    lenient().when(keyboardFactory.buildBackToMenu()).thenReturn(mock(InlineKeyboardMarkup.class));
    var callback = buildCallback(2L, 200L);

    // When
    var result = handler.handle(callback);

    // Then
    assertThat(result.getText()).isEqualTo("🚫 Ваш чёрный список пока пуст.");
  }

  @Test
  void should_return_empty_message_when_user_is_not_registered() {
    // Given
    when(userService.findByTelegramId(3L)).thenReturn(Optional.empty());
    lenient().when(keyboardFactory.buildBackToMenu()).thenReturn(mock(InlineKeyboardMarkup.class));
    var callback = buildCallback(3L, 300L);

    // When
    var result = handler.handle(callback);

    // Then — graceful message, no exception, service never consulted
    assertThat(result.getText()).isEqualTo("🚫 Ваш чёрный список пока пуст.");
    verify(blacklistService, never()).findByUser(any(), any(), any());
  }

  @Test
  void should_use_back_to_menu_keyboard_in_reply() {
    // Given
    var user = buildUser(9L);
    when(userService.findByTelegramId(4L)).thenReturn(Optional.of(user));
    when(blacklistService.findByUser(eq(9L), isNull(), any())).thenReturn(Page.empty());
    var expectedKeyboard = mock(InlineKeyboardMarkup.class);
    when(keyboardFactory.buildBackToMenu()).thenReturn(expectedKeyboard);
    var callback = buildCallback(4L, 400L);

    // When
    var result = handler.handle(callback);

    // Then
    assertThat(result.getReplyMarkup()).isSameAs(expectedKeyboard);
  }

  @Test
  void should_expose_action_blacklist_matching_main_menu_keyboard_factory_constant() {
    // Then — the callback action id this handler is routed for by FlatioBot
    assertThat(BlacklistCallbackHandler.ACTION_BLACKLIST)
        .isEqualTo(MainMenuKeyboardFactory.ACTION_BLACKLIST)
        .isEqualTo("action:blacklist");
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

  private CallbackQuery buildCallback(Long telegramId, Long chatId) {
    var from = mock(org.telegram.telegrambots.meta.api.objects.User.class);
    when(from.getId()).thenReturn(telegramId);

    var message = mock(Message.class);
    when(message.getChatId()).thenReturn(chatId);

    var callback = mock(CallbackQuery.class);
    when(callback.getFrom()).thenReturn(from);
    when(callback.getMessage()).thenReturn(message);
    return callback;
  }
}
