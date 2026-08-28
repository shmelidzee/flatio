package com.flatio.telegram.callback;

import com.flatio.common.exception.FavoriteLimitExceededException;
import com.flatio.common.exception.FavoriteNotFoundException;
import com.flatio.common.exception.ListingNotFoundException;
import com.flatio.domain.user.User;
import com.flatio.service.FavoriteService;
import com.flatio.service.UserService;
import com.flatio.telegram.keyboard.MainMenuKeyboardFactory;
import com.flatio.web.dto.CreateFavoriteRequest;
import com.flatio.web.dto.FavoriteResponse;
import com.flatio.web.dto.ListingSummaryResponse;
import java.math.BigDecimal;
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
import org.springframework.data.domain.PageRequest;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link FavoritesCallbackHandler} (issue #457).
 */
@ExtendWith(MockitoExtension.class)
class FavoritesCallbackHandlerTest {

  @Mock
  private UserService userService;

  @Mock
  private FavoriteService favoriteService;

  @Mock
  private MainMenuKeyboardFactory keyboardFactory;

  @Mock
  private TelegramClient telegramClient;

  @InjectMocks
  private FavoritesCallbackHandler handler;

  @Test
  void should_send_item_and_navigation_messages_when_user_has_favorites() throws Exception {
    // Given
    var user = buildUser(7L);
    when(userService.findByTelegramId(1L)).thenReturn(Optional.of(user));
    var favorite = buildFavoriteResponse(3L, "Квартира в центре", BigDecimal.valueOf(50_000), "USD");
    when(favoriteService.findByUser(eq(7L), any())).thenReturn(new PageImpl<>(List.of(favorite)));
    var callback = buildCallback(1L, 100L, true, FavoritesCallbackHandler.ACTION_FAVORITES);

    // When
    handler.handle(callback);

    // Then — one message for the item, one navigation message
    var captor = ArgumentCaptor.forClass(SendMessage.class);
    verify(telegramClient, org.mockito.Mockito.times(2)).execute(captor.capture());
    var messages = captor.getAllValues();
    assertThat(messages.get(0).getText()).contains("Квартира в центре").contains("50000 USD");
    assertThat(messages.get(1).getText()).contains("Страница 1 из 1");
  }

  @Test
  void should_send_empty_message_when_user_has_no_favorites() throws Exception {
    // Given
    var user = buildUser(8L);
    when(userService.findByTelegramId(2L)).thenReturn(Optional.of(user));
    when(favoriteService.findByUser(eq(8L), any())).thenReturn(Page.empty());
    var expectedKeyboard = mock(InlineKeyboardMarkup.class);
    when(keyboardFactory.buildBackToMenu()).thenReturn(expectedKeyboard);
    var callback = buildCallback(2L, 200L, true, FavoritesCallbackHandler.ACTION_FAVORITES);

    // When
    handler.handle(callback);

    // Then
    var captor = ArgumentCaptor.forClass(SendMessage.class);
    verify(telegramClient).execute(captor.capture());
    assertThat(captor.getValue().getText()).isEqualTo("⭐ У вас пока нет избранных объявлений.");
    assertThat(captor.getValue().getReplyMarkup()).isSameAs(expectedKeyboard);
  }

  @Test
  void should_send_empty_message_when_user_is_not_registered() throws Exception {
    // Given
    when(userService.findByTelegramId(3L)).thenReturn(Optional.empty());
    lenient().when(keyboardFactory.buildBackToMenu()).thenReturn(mock(InlineKeyboardMarkup.class));
    var callback = buildCallback(3L, 300L, true, FavoritesCallbackHandler.ACTION_FAVORITES);

    // When
    handler.handle(callback);

    // Then — graceful message, no exception, service never consulted
    var captor = ArgumentCaptor.forClass(SendMessage.class);
    verify(telegramClient).execute(captor.capture());
    assertThat(captor.getValue().getText()).isEqualTo("⭐ У вас пока нет избранных объявлений.");
    verify(favoriteService, never()).findByUser(any(), any());
  }

  @Test
  void should_send_private_chat_required_message_when_chat_is_not_private() throws Exception {
    // Given — issue #463: favorites are personal data, must not be shown in a group/channel
    lenient().when(keyboardFactory.buildBackToMenu()).thenReturn(mock(InlineKeyboardMarkup.class));
    var callback = buildCallback(1L, 100L, false, FavoritesCallbackHandler.ACTION_FAVORITES);

    // When
    handler.handle(callback);

    // Then — redirected to a private chat, no user/data lookup performed
    var captor = ArgumentCaptor.forClass(SendMessage.class);
    verify(telegramClient).execute(captor.capture());
    assertThat(captor.getValue().getText()).isEqualTo("🔒 Этот раздел содержит личные данные и доступен только в переписке "
        + "с ботом один на один. Откройте бота в личных сообщениях, чтобы посмотреть его.");
    verify(userService, never()).findByTelegramId(any());
    verify(favoriteService, never()).findByUser(any(), any());
  }

  @Test
  void should_return_added_toast_when_listing_added_to_favorites() {
    // Given
    var user = buildUser(7L);
    when(userService.findByTelegramId(1L)).thenReturn(Optional.of(user));
    var callback = buildCallback(1L, 100L, true, "FAV:ADD:42");

    // When
    var toast = handler.handleAdd(callback);

    // Then
    assertThat(toast).isEqualTo("⭐ Добавлено в избранное");
    verify(favoriteService).create(7L, new CreateFavoriteRequest(42L));
  }

  @Test
  void should_return_limit_exceeded_toast_when_favorite_limit_exceeded() {
    // Given
    var user = buildUser(7L);
    when(userService.findByTelegramId(1L)).thenReturn(Optional.of(user));
    when(favoriteService.create(eq(7L), any())).thenThrow(new FavoriteLimitExceededException(50));
    var callback = buildCallback(1L, 100L, true, "FAV:ADD:42");

    // When
    var toast = handler.handleAdd(callback);

    // Then — friendly text instead of a propagated exception
    assertThat(toast).contains("лимит избранного");
  }

  @Test
  void should_return_not_found_toast_when_listing_not_found_on_add() {
    // Given
    var user = buildUser(7L);
    when(userService.findByTelegramId(1L)).thenReturn(Optional.of(user));
    when(favoriteService.create(eq(7L), any())).thenThrow(new ListingNotFoundException(42L));
    var callback = buildCallback(1L, 100L, true, "FAV:ADD:42");

    // When
    var toast = handler.handleAdd(callback);

    // Then
    assertThat(toast).isEqualTo("Это объявление больше недоступно.");
  }

  @Test
  void should_return_private_chat_required_toast_when_add_from_non_private_chat() {
    // Given
    var callback = buildCallback(1L, 100L, false, "FAV:ADD:42");

    // When
    var toast = handler.handleAdd(callback);

    // Then
    assertThat(toast).contains("личные данные");
    verify(userService, never()).findByTelegramId(any());
  }

  @Test
  void should_return_removed_toast_when_favorite_removed() {
    // Given
    var user = buildUser(7L);
    when(userService.findByTelegramId(1L)).thenReturn(Optional.of(user));
    var callback = buildCallback(1L, 100L, true, "FAV:REMOVE:42");

    // When
    var toast = handler.handleRemove(callback);

    // Then
    assertThat(toast).isEqualTo("Убрано из избранного");
    verify(favoriteService).delete(7L, 42L);
  }

  @Test
  void should_return_removed_toast_when_favorite_already_removed() {
    // Given — deleting an already-removed favorite must not surface as an error to the user
    var user = buildUser(7L);
    when(userService.findByTelegramId(1L)).thenReturn(Optional.of(user));
    org.mockito.Mockito.doThrow(new FavoriteNotFoundException(42L)).when(favoriteService).delete(7L, 42L);
    var callback = buildCallback(1L, 100L, true, "FAV:REMOVE:42");

    // When
    var toast = handler.handleRemove(callback);

    // Then
    assertThat(toast).isEqualTo("Убрано из избранного");
  }

  @Test
  void should_send_session_expired_message_when_page_callback_has_no_active_session() throws Exception {
    // Given — FAV:PAGE:* received without a preceding handle() call (session expired/never opened)
    var callback = buildCallback(1L, 100L, true, "FAV:PAGE:NEXT");

    // When
    handler.handlePage(callback);

    // Then
    var captor = ArgumentCaptor.forClass(SendMessage.class);
    verify(telegramClient).execute(captor.capture());
    assertThat(captor.getValue().getText()).contains("устарел");
    verify(userService, never()).findByTelegramId(any());
  }

  @Test
  void should_render_next_page_when_fav_page_next_callback_received_after_open() throws Exception {
    // Given — two favorites across two pages of size 1 each (private field PAGE_SIZE=5 in
    // production, but the mocked service can still report totalPages=2 for a smaller page)
    var user = buildUser(7L);
    when(userService.findByTelegramId(1L)).thenReturn(Optional.of(user));
    var page1Favorite = buildFavoriteResponse(1L, "Квартира 1", BigDecimal.valueOf(10_000), "USD");
    var page2Favorite = buildFavoriteResponse(2L, "Квартира 2", BigDecimal.valueOf(20_000), "USD");
    // total=6 with pageSize=5 yields totalPages=2 (PageImpl computes totalPages from total/pageSize,
    // not from the content list size), matching the two-page scenario this test exercises.
    when(favoriteService.findByUser(eq(7L), eq(PageRequest.of(0, 5, org.springframework.data.domain.Sort
        .by(org.springframework.data.domain.Sort.Direction.DESC, "createdAt")))))
        .thenReturn(new PageImpl<>(List.of(page1Favorite), PageRequest.of(0, 5), 6));
    when(favoriteService.findByUser(eq(7L), eq(PageRequest.of(1, 5, org.springframework.data.domain.Sort
        .by(org.springframework.data.domain.Sort.Direction.DESC, "createdAt")))))
        .thenReturn(new PageImpl<>(List.of(page2Favorite), PageRequest.of(1, 5), 6));
    var openCallback = buildCallback(1L, 100L, true, FavoritesCallbackHandler.ACTION_FAVORITES);
    handler.handle(openCallback);
    var pageCallback = buildCallback(1L, 100L, true, "FAV:PAGE:NEXT");

    // When
    handler.handlePage(pageCallback);

    // Then — the second render shows the second page's item and navigation
    var captor = ArgumentCaptor.forClass(SendMessage.class);
    verify(telegramClient, org.mockito.Mockito.times(4)).execute(captor.capture());
    var lastTwo = captor.getAllValues().subList(2, 4);
    assertThat(lastTwo.get(0).getText()).contains("Квартира 2");
    assertThat(lastTwo.get(1).getText()).contains("Страница 2 из 2");
  }

  // -------------------------------------------------------------------------
  // helpers
  // -------------------------------------------------------------------------

  private User buildUser(Long id) {
    var user = new User();
    user.setId(id);
    return user;
  }

  private FavoriteResponse buildFavoriteResponse(Long id, String title, BigDecimal price, String currency) {
    var listing = new ListingSummaryResponse(
        1L, title, price, currency, null, null, 2, "APARTMENT", null, "Минск", null, null,
        "realt", null, null, null, false
    );
    return new FavoriteResponse(id, listing, price, price, BigDecimal.ZERO, false, false, Instant.now());
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
