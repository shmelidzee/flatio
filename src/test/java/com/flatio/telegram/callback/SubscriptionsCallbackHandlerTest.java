package com.flatio.telegram.callback;

import com.flatio.domain.subscription.DeliveryMode;
import com.flatio.domain.user.User;
import com.flatio.service.SubscriptionService;
import com.flatio.service.UserService;
import com.flatio.telegram.keyboard.MainMenuKeyboardFactory;
import com.flatio.web.dto.SubscriptionResponse;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SubscriptionsCallbackHandler} (issue #456).
 */
@ExtendWith(MockitoExtension.class)
class SubscriptionsCallbackHandlerTest {

  @Mock
  private UserService userService;

  @Mock
  private SubscriptionService subscriptionService;

  @Mock
  private MainMenuKeyboardFactory keyboardFactory;

  @InjectMocks
  private SubscriptionsCallbackHandler handler;

  @Test
  void should_return_formatted_subscriptions_list_when_user_has_subscriptions() {
    // Given
    var user = buildUser(7L);
    when(userService.findByTelegramId(1L)).thenReturn(Optional.of(user));
    var subscription = buildSubscriptionResponse(3L, "2-комнатные в центре", true, DeliveryMode.REALTIME);
    when(subscriptionService.findByUser(eq(7L), any())).thenReturn(new PageImpl<>(List.of(subscription)));
    lenient().when(keyboardFactory.buildBackToMenu()).thenReturn(mock(InlineKeyboardMarkup.class));
    var callback = buildCallback(1L, 100L);

    // When
    var result = handler.handle(callback);

    // Then
    assertThat(result.getText()).contains("Мои подписки");
    assertThat(result.getText()).contains("2-комнатные в центре");
    assertThat(result.getText()).contains("активна");
    assertThat(result.getText()).contains("мгновенно");
  }

  @Test
  void should_show_paused_status_when_subscription_is_inactive() {
    // Given
    var user = buildUser(7L);
    when(userService.findByTelegramId(1L)).thenReturn(Optional.of(user));
    var subscription = buildSubscriptionResponse(3L, "Однушки в спальных районах", false, DeliveryMode.DIGEST);
    when(subscriptionService.findByUser(eq(7L), any())).thenReturn(new PageImpl<>(List.of(subscription)));
    lenient().when(keyboardFactory.buildBackToMenu()).thenReturn(mock(InlineKeyboardMarkup.class));
    var callback = buildCallback(1L, 100L);

    // When
    var result = handler.handle(callback);

    // Then
    assertThat(result.getText()).contains("на паузе");
    assertThat(result.getText()).contains("дайджест");
  }

  @Test
  void should_return_empty_message_when_user_has_no_subscriptions() {
    // Given
    var user = buildUser(8L);
    when(userService.findByTelegramId(2L)).thenReturn(Optional.of(user));
    when(subscriptionService.findByUser(eq(8L), any())).thenReturn(Page.empty());
    lenient().when(keyboardFactory.buildBackToMenu()).thenReturn(mock(InlineKeyboardMarkup.class));
    var callback = buildCallback(2L, 200L);

    // When
    var result = handler.handle(callback);

    // Then
    assertThat(result.getText()).isEqualTo("🔔 У вас пока нет подписок на поиск.");
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
    assertThat(result.getText()).isEqualTo("🔔 У вас пока нет подписок на поиск.");
    verify(subscriptionService, never()).findByUser(any(), any());
  }

  @Test
  void should_use_back_to_menu_keyboard_in_reply() {
    // Given
    var user = buildUser(9L);
    when(userService.findByTelegramId(4L)).thenReturn(Optional.of(user));
    when(subscriptionService.findByUser(eq(9L), any())).thenReturn(Page.empty());
    var expectedKeyboard = mock(InlineKeyboardMarkup.class);
    when(keyboardFactory.buildBackToMenu()).thenReturn(expectedKeyboard);
    var callback = buildCallback(4L, 400L);

    // When
    var result = handler.handle(callback);

    // Then
    assertThat(result.getReplyMarkup()).isSameAs(expectedKeyboard);
  }

  @Test
  void should_expose_action_subscriptions_matching_main_menu_keyboard_factory_constant() {
    // Then — the callback action id this handler is routed for by FlatioBot
    assertThat(SubscriptionsCallbackHandler.ACTION_SUBSCRIPTIONS)
        .isEqualTo(MainMenuKeyboardFactory.ACTION_SUBSCRIPTIONS)
        .isEqualTo("action:subscriptions");
  }

  @Test
  void should_return_private_chat_required_message_when_chat_is_not_private() {
    // Given — issue #463: subscriptions are personal data, must not be shown in a group/channel
    lenient().when(keyboardFactory.buildBackToMenu()).thenReturn(mock(InlineKeyboardMarkup.class));
    var callback = buildCallback(1L, 100L, false);

    // When
    var result = handler.handle(callback);

    // Then — redirected to a private chat, no user/data lookup performed
    assertThat(result.getText()).isEqualTo("🔒 Этот раздел содержит личные данные и доступен только в переписке "
        + "с ботом один на один. Откройте бота в личных сообщениях, чтобы посмотреть его.");
    verify(userService, never()).findByTelegramId(any());
    verify(subscriptionService, never()).findByUser(any(), any());
  }

  // -------------------------------------------------------------------------
  // helpers
  // -------------------------------------------------------------------------

  private User buildUser(Long id) {
    var user = new User();
    user.setId(id);
    return user;
  }

  private SubscriptionResponse buildSubscriptionResponse(Long id, String name, boolean active, DeliveryMode mode) {
    return new SubscriptionResponse(
        id, name, active, null, Set.of(), mode, null, null, null, null, Instant.now(), Instant.now()
    );
  }

  private CallbackQuery buildCallback(Long telegramId, Long chatId) {
    return buildCallback(telegramId, chatId, true);
  }

  private CallbackQuery buildCallback(Long telegramId, Long chatId, boolean isPrivateChat) {
    var from = mock(org.telegram.telegrambots.meta.api.objects.User.class);
    lenient().when(from.getId()).thenReturn(telegramId);

    var chat = mock(Chat.class);
    lenient().when(chat.isUserChat()).thenReturn(isPrivateChat);

    var message = mock(Message.class);
    lenient().when(message.getChatId()).thenReturn(chatId);
    lenient().when(message.getChat()).thenReturn(chat);

    var callback = mock(CallbackQuery.class);
    when(callback.getFrom()).thenReturn(from);
    when(callback.getMessage()).thenReturn(message);
    return callback;
  }
}
