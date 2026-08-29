package com.flatio.telegram.callback;

import com.flatio.common.exception.SubscriptionLimitExceededException;
import com.flatio.common.exception.SubscriptionNotFoundException;
import com.flatio.domain.city.City;
import com.flatio.domain.listing.DealType;
import com.flatio.domain.subscription.DeliveryMode;
import com.flatio.domain.user.User;
import com.flatio.service.CityService;
import com.flatio.service.SubscriptionService;
import com.flatio.service.UserService;
import com.flatio.telegram.keyboard.MainMenuKeyboardFactory;
import com.flatio.telegram.state.SearchFilterState;
import com.flatio.telegram.state.SearchFilterWizard;
import com.flatio.telegram.state.SubscriptionCreationState;
import com.flatio.telegram.state.SubscriptionEditState;
import com.flatio.web.dto.CreateSubscriptionRequest;
import com.flatio.web.dto.SubscriptionResponse;
import com.flatio.web.dto.SubscriptionSearchCriteria;
import com.flatio.web.dto.UpdateSubscriptionRequest;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SubscriptionsCallbackHandler} (issue #458).
 */
@ExtendWith(MockitoExtension.class)
class SubscriptionsCallbackHandlerTest {

  @Mock
  private UserService userService;

  @Mock
  private SubscriptionService subscriptionService;

  @Mock
  private CityService cityService;

  @Mock
  private MainMenuKeyboardFactory keyboardFactory;

  @Mock
  private SearchFilterWizard wizard;

  @Mock
  private FilterCallbackHandler filterCallbackHandler;

  @Mock
  private SubscriptionCreationState creationState;

  @Mock
  private SubscriptionEditState editState;

  @Mock
  private TelegramClient telegramClient;

  @InjectMocks
  private SubscriptionsCallbackHandler handler;

  @Test
  void should_send_item_and_navigation_messages_when_user_has_subscriptions() throws Exception {
    // Given
    var user = buildUser(7L);
    when(userService.findByTelegramId(1L)).thenReturn(Optional.of(user));
    var subscription = buildSubscriptionResponse(3L, "2-комнатные в центре", true, DeliveryMode.REALTIME);
    when(subscriptionService.findByUser(eq(7L), any())).thenReturn(new PageImpl<>(List.of(subscription)));
    var callback = buildCallback(1L, 100L, true, SubscriptionsCallbackHandler.ACTION_SUBSCRIPTIONS);

    // When
    handler.handle(callback);

    // Then
    var captor = ArgumentCaptor.forClass(SendMessage.class);
    verify(telegramClient, times(2)).execute(captor.capture());
    assertThat(captor.getAllValues().get(0).getText())
        .contains("2-комнатные в центре").contains("активна").contains("мгновенно");
  }

  @Test
  void should_send_empty_message_when_user_has_no_subscriptions() throws Exception {
    // Given
    var user = buildUser(8L);
    when(userService.findByTelegramId(2L)).thenReturn(Optional.of(user));
    when(subscriptionService.findByUser(eq(8L), any())).thenReturn(Page.empty());
    var callback = buildCallback(2L, 200L, true, SubscriptionsCallbackHandler.ACTION_SUBSCRIPTIONS);

    // When
    handler.handle(callback);

    // Then
    var captor = ArgumentCaptor.forClass(SendMessage.class);
    verify(telegramClient).execute(captor.capture());
    assertThat(captor.getValue().getText()).isEqualTo("🔔 У вас пока нет подписок на поиск.");
  }

  @Test
  void should_send_empty_message_when_user_is_not_registered() throws Exception {
    // Given
    when(userService.findByTelegramId(3L)).thenReturn(Optional.empty());
    lenient().when(keyboardFactory.buildBackToMenu()).thenReturn(mock(InlineKeyboardMarkup.class));
    var callback = buildCallback(3L, 300L, true, SubscriptionsCallbackHandler.ACTION_SUBSCRIPTIONS);

    // When
    handler.handle(callback);

    // Then
    verify(subscriptionService, never()).findByUser(any(), any());
  }

  @Test
  void should_send_private_chat_required_message_when_chat_is_not_private() throws Exception {
    // Given — issue #463
    lenient().when(keyboardFactory.buildBackToMenu()).thenReturn(mock(InlineKeyboardMarkup.class));
    var callback = buildCallback(1L, 100L, false, SubscriptionsCallbackHandler.ACTION_SUBSCRIPTIONS);

    // When
    handler.handle(callback);

    // Then
    var captor = ArgumentCaptor.forClass(SendMessage.class);
    verify(telegramClient).execute(captor.capture());
    assertThat(captor.getValue().getText()).contains("личные данные");
    verify(userService, never()).findByTelegramId(any());
  }

  @Test
  void should_prompt_for_name_when_filter_is_active() throws Exception {
    // Given
    var state = buildFilterState();
    when(wizard.getState(1L)).thenReturn(Optional.of(state));
    var callback = buildCallback(1L, 100L, true, SubscriptionsCallbackHandler.CREATE_FROM_FILTER);

    // When
    handler.handleCreateFromFilter(callback);

    // Then
    var criteriaCaptor = ArgumentCaptor.forClass(SubscriptionSearchCriteria.class);
    verify(creationState).await(eq(1L), criteriaCaptor.capture());
    assertThat(criteriaCaptor.getValue().dealType()).isEqualTo(DealType.RENT);
    assertThat(criteriaCaptor.getValue().rooms()).isEqualTo(2);
    var messageCaptor = ArgumentCaptor.forClass(SendMessage.class);
    verify(telegramClient).execute(messageCaptor.capture());
    assertThat(messageCaptor.getValue().getText()).isEqualTo("Введите название подписки:");
  }

  @Test
  void should_send_no_filter_message_when_no_active_filter() throws Exception {
    // Given
    when(wizard.getState(1L)).thenReturn(Optional.empty());
    var callback = buildCallback(1L, 100L, true, SubscriptionsCallbackHandler.CREATE_FROM_FILTER);

    // When
    handler.handleCreateFromFilter(callback);

    // Then
    var captor = ArgumentCaptor.forClass(SendMessage.class);
    verify(telegramClient).execute(captor.capture());
    assertThat(captor.getValue().getText()).contains("выполните поиск");
    verify(creationState, never()).await(any(), any());
  }

  @Test
  void should_create_subscription_when_valid_name_provided() {
    // Given
    var criteria = buildSearchCriteria();
    when(creationState.peek(1L)).thenReturn(Optional.of(criteria));
    when(userService.findByTelegramId(1L)).thenReturn(Optional.of(buildUser(7L)));

    // When
    handler.handleSubscriptionNameText(1L, "100", "2-комнатные в центре");

    // Then
    var requestCaptor = ArgumentCaptor.forClass(CreateSubscriptionRequest.class);
    verify(subscriptionService).create(eq(7L), requestCaptor.capture());
    assertThat(requestCaptor.getValue().name()).isEqualTo("2-комнатные в центре");
    assertThat(requestCaptor.getValue().searchCriteria()).isEqualTo(criteria);
    verify(creationState).clear(1L);
  }

  @Test
  void should_reprompt_when_subscription_name_is_blank() {
    // Given
    when(creationState.peek(1L)).thenReturn(Optional.of(buildSearchCriteria()));

    // When
    handler.handleSubscriptionNameText(1L, "100", "   ");

    // Then
    verify(subscriptionService, never()).create(any(), any());
    verify(creationState, never()).clear(any());
  }

  @Test
  void should_send_limit_exceeded_message_when_subscription_limit_exceeded() {
    // Given
    when(creationState.peek(1L)).thenReturn(Optional.of(buildSearchCriteria()));
    when(userService.findByTelegramId(1L)).thenReturn(Optional.of(buildUser(7L)));
    doThrow(new SubscriptionLimitExceededException(5)).when(subscriptionService).create(eq(7L), any());

    // When / Then — no exception propagates
    handler.handleSubscriptionNameText(1L, "100", "Моя подписка");
    verify(creationState).clear(1L);
  }

  @Test
  void should_do_nothing_when_no_pending_creation_state() {
    // Given
    when(creationState.peek(1L)).thenReturn(Optional.empty());

    // When
    handler.handleSubscriptionNameText(1L, "100", "Моя подписка");

    // Then
    verify(subscriptionService, never()).create(any(), any());
    verify(userService, never()).findByTelegramId(any());
  }

  @Test
  void should_return_paused_toast_when_pause_succeeds() {
    // Given
    when(userService.findByTelegramId(1L)).thenReturn(Optional.of(buildUser(7L)));
    var callback = buildCallback(1L, 100L, true, "SUB:PAUSE:5");

    // When
    var toast = handler.handlePause(callback);

    // Then
    assertThat(toast).isEqualTo("⏸ Подписка поставлена на паузу");
    verify(subscriptionService).pause(7L, 5L);
  }

  @Test
  void should_return_not_found_toast_when_pause_target_missing() {
    // Given
    when(userService.findByTelegramId(1L)).thenReturn(Optional.of(buildUser(7L)));
    doThrow(new SubscriptionNotFoundException(5L)).when(subscriptionService).pause(7L, 5L);
    var callback = buildCallback(1L, 100L, true, "SUB:PAUSE:5");

    // When
    var toast = handler.handlePause(callback);

    // Then
    assertThat(toast).isEqualTo("Подписка не найдена.");
  }

  @Test
  void should_return_resumed_toast_when_resume_succeeds() {
    // Given
    when(userService.findByTelegramId(1L)).thenReturn(Optional.of(buildUser(7L)));
    var callback = buildCallback(1L, 100L, true, "SUB:RESUME:5");

    // When
    var toast = handler.handleResume(callback);

    // Then
    assertThat(toast).isEqualTo("▶️ Подписка возобновлена");
    verify(subscriptionService).resume(7L, 5L);
  }

  @Test
  void should_return_limit_exceeded_toast_when_resume_limit_exceeded() {
    // Given
    when(userService.findByTelegramId(1L)).thenReturn(Optional.of(buildUser(7L)));
    doThrow(new SubscriptionLimitExceededException(5)).when(subscriptionService).resume(7L, 5L);
    var callback = buildCallback(1L, 100L, true, "SUB:RESUME:5");

    // When
    var toast = handler.handleResume(callback);

    // Then
    assertThat(toast).contains("лимит активных подписок");
  }

  @Test
  void should_return_deleted_toast_when_delete_succeeds() {
    // Given
    when(userService.findByTelegramId(1L)).thenReturn(Optional.of(buildUser(7L)));
    var callback = buildCallback(1L, 100L, true, "SUB:DELETE:5");

    // When
    var toast = handler.handleDelete(callback);

    // Then
    assertThat(toast).isEqualTo("🗑 Подписка удалена");
    verify(subscriptionService).delete(7L, 5L);
  }

  @Test
  void should_return_private_chat_required_toast_when_pause_from_non_private_chat() {
    // Given
    var callback = buildCallback(1L, 100L, false, "SUB:PAUSE:5");

    // When
    var toast = handler.handlePause(callback);

    // Then
    assertThat(toast).contains("личные данные");
    verify(userService, never()).findByTelegramId(any());
  }

  @Test
  void should_include_criteria_summary_when_subscription_has_search_criteria() throws Exception {
    // Given — issue #478 FR-NAV-7
    var user = buildUser(7L);
    when(userService.findByTelegramId(1L)).thenReturn(Optional.of(user));
    var criteria = new SubscriptionSearchCriteria(DealType.RENT, "APARTMENT", null, null, 5L,
        BigDecimal.valueOf(400), BigDecimal.valueOf(700), 2, null, null);
    var subscription = buildSubscriptionResponseWithCriteria(3L, "2-комнатные в центре", true, DeliveryMode.REALTIME, criteria);
    var city = new City();
    city.setId(5L);
    city.setNameRu("Минск");
    when(cityService.findById(5L)).thenReturn(Optional.of(city));
    when(subscriptionService.findByUser(eq(7L), any())).thenReturn(new PageImpl<>(List.of(subscription)));
    var callback = buildCallback(1L, 100L, true, SubscriptionsCallbackHandler.ACTION_SUBSCRIPTIONS);

    // When
    handler.handle(callback);

    // Then
    var captor = ArgumentCaptor.forClass(SendMessage.class);
    verify(telegramClient, times(2)).execute(captor.capture());
    assertThat(captor.getAllValues().get(0).getText())
        .contains("Аренда").contains("Минск").contains("400–700 BYN").contains("2 комн.");
  }

  @Test
  void should_skip_broken_item_when_formatting_fails_for_one_subscription() throws Exception {
    // Given — issue #478 AC: a formatting error on one subscription must not break the rest of the page
    var user = buildUser(7L);
    when(userService.findByTelegramId(1L)).thenReturn(Optional.of(user));
    var goodCriteria = new SubscriptionSearchCriteria(DealType.RENT, "APARTMENT", null, "Минск", null, null, null, null, null, null);
    var goodSub = buildSubscriptionResponseWithCriteria(1L, "Good", true, DeliveryMode.REALTIME, goodCriteria);
    var brokenCriteria = new SubscriptionSearchCriteria(null, null, null, null, 99L, null, null, null, null, null);
    var brokenSub = buildSubscriptionResponseWithCriteria(2L, "Broken", true, DeliveryMode.REALTIME, brokenCriteria);
    when(cityService.findById(99L)).thenThrow(new RuntimeException("boom"));
    when(subscriptionService.findByUser(eq(7L), any())).thenReturn(new PageImpl<>(List.of(goodSub, brokenSub)));
    var callback = buildCallback(1L, 100L, true, SubscriptionsCallbackHandler.ACTION_SUBSCRIPTIONS);

    // When
    handler.handle(callback);

    // Then — no exception propagates, the broken item is skipped, the good item still renders
    var captor = ArgumentCaptor.forClass(SendMessage.class);
    verify(telegramClient, times(2)).execute(captor.capture());
    var itemsMessage = captor.getAllValues().get(0);
    assertThat(itemsMessage.getText()).contains("Good").doesNotContain("Broken");
    var keyboard = (InlineKeyboardMarkup) itemsMessage.getReplyMarkup();
    assertThat(keyboard.getKeyboard()).hasSize(1);
  }

  @Test
  void should_send_session_expired_message_when_page_callback_has_no_active_session() throws Exception {
    // Given — SUB:PAGE:* received without a preceding handle() call (session expired/never opened)
    var callback = buildCallback(1L, 100L, true, "SUB:PAGE:NEXT");

    // When
    handler.handlePage(callback);

    // Then
    var captor = ArgumentCaptor.forClass(SendMessage.class);
    verify(telegramClient).execute(captor.capture());
    assertThat(captor.getValue().getText()).contains("устарел");
    verify(userService, never()).findByTelegramId(any());
  }

  @Test
  void should_render_next_page_when_sub_page_next_callback_received_after_open() throws Exception {
    // Given — total=6 with pageSize=5 yields totalPages=2
    var user = buildUser(7L);
    when(userService.findByTelegramId(1L)).thenReturn(Optional.of(user));
    var page1Sub = buildSubscriptionResponse(1L, "Подписка 1", true, DeliveryMode.REALTIME);
    var page2Sub = buildSubscriptionResponse(2L, "Подписка 2", true, DeliveryMode.REALTIME);
    var sort = org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "createdAt");
    when(subscriptionService.findByUser(eq(7L), eq(PageRequest.of(0, 5, sort))))
        .thenReturn(new PageImpl<>(List.of(page1Sub), PageRequest.of(0, 5), 6));
    when(subscriptionService.findByUser(eq(7L), eq(PageRequest.of(1, 5, sort))))
        .thenReturn(new PageImpl<>(List.of(page2Sub), PageRequest.of(1, 5), 6));
    var openCallback = buildCallback(1L, 100L, true, SubscriptionsCallbackHandler.ACTION_SUBSCRIPTIONS);
    handler.handle(openCallback);
    var pageCallback = buildCallback(1L, 100L, true, "SUB:PAGE:NEXT");

    // When
    handler.handlePage(pageCallback);

    // Then — the second render shows the second page's item and navigation
    var captor = ArgumentCaptor.forClass(SendMessage.class);
    verify(telegramClient, times(4)).execute(captor.capture());
    var lastTwo = captor.getAllValues().subList(2, 4);
    assertThat(lastTwo.get(0).getText()).contains("Подписка 2");
    assertThat(lastTwo.get(1).getText()).contains("Страница 2 из 2");
  }

  @Test
  void should_start_edit_wizard_when_subscription_exists() throws Exception {
    // Given — issue #479
    var user = buildUser(7L);
    when(userService.findByTelegramId(1L)).thenReturn(Optional.of(user));
    var criteria = buildSearchCriteria();
    var subscription = buildSubscriptionResponseWithCriteria(5L, "2-комнатные в центре", true, DeliveryMode.REALTIME, criteria);
    when(subscriptionService.findByIdForUser(7L, 5L)).thenReturn(subscription);
    var editMessage = mock(SendMessage.class);
    when(filterCallbackHandler.startWizardMessageForEdit(eq(1L), eq("100"), any())).thenReturn(editMessage);
    var callback = buildCallback(1L, 100L, true, "SUB:EDIT:5");

    // When
    handler.handleEdit(callback);

    // Then
    verify(editState).start(eq(1L), eq(subscription));
    var stateCaptor = ArgumentCaptor.forClass(SearchFilterState.class);
    verify(filterCallbackHandler).startWizardMessageForEdit(eq(1L), eq("100"), stateCaptor.capture());
    assertThat(stateCaptor.getValue().getEditingSubscriptionId()).isEqualTo(5L);
    assertThat(stateCaptor.getValue().getDealType()).isEqualTo(criteria.dealType());
    assertThat(stateCaptor.getValue().getRooms()).isEqualTo(criteria.rooms());
    verify(telegramClient).execute(editMessage);
  }

  @Test
  void should_send_not_found_message_when_edit_target_missing() throws Exception {
    // Given
    when(userService.findByTelegramId(1L)).thenReturn(Optional.of(buildUser(7L)));
    doThrow(new SubscriptionNotFoundException(5L)).when(subscriptionService).findByIdForUser(7L, 5L);
    var callback = buildCallback(1L, 100L, true, "SUB:EDIT:5");

    // When
    handler.handleEdit(callback);

    // Then
    verify(editState, never()).start(any(), any());
    verify(filterCallbackHandler, never()).startWizardMessageForEdit(any(), any(), any());
  }

  @Test
  void should_reject_edit_from_non_private_chat() throws Exception {
    // Given — issue #463
    var callback = buildCallback(1L, 100L, false, "SUB:EDIT:5");

    // When
    handler.handleEdit(callback);

    // Then
    verify(userService, never()).findByTelegramId(any());
    verify(subscriptionService, never()).findByIdForUser(any(), any());
  }

  @Test
  void should_save_edited_subscription_when_wizard_completes() {
    // Given — issue #479: name/triggers/delivery settings carried over unchanged from the snapshot
    var state = new SearchFilterState();
    state.setEditingSubscriptionId(5L);
    state.setDealType(DealType.RENT);
    state.setRooms(3);
    when(wizard.getState(1L)).thenReturn(Optional.of(state));
    var original = buildSubscriptionResponse(5L, "2-комнатные в центре", true, DeliveryMode.REALTIME);
    when(editState.get(1L)).thenReturn(Optional.of(original));
    when(userService.findByTelegramId(1L)).thenReturn(Optional.of(buildUser(7L)));
    var callback = buildCallback(1L, 100L, true, "FILTER:SEARCH");

    // When
    handler.handleSaveEdit(callback);

    // Then
    var requestCaptor = ArgumentCaptor.forClass(UpdateSubscriptionRequest.class);
    verify(subscriptionService).update(eq(7L), eq(5L), requestCaptor.capture());
    assertThat(requestCaptor.getValue().name()).isEqualTo("2-комнатные в центре");
    assertThat(requestCaptor.getValue().searchCriteria().rooms()).isEqualTo(3);
    assertThat(requestCaptor.getValue().deliveryMode()).isEqualTo(DeliveryMode.REALTIME);
    verify(wizard).reset(1L);
    verify(editState).clear(1L);
  }

  @Test
  void should_reject_save_edit_from_non_private_chat() {
    // Given — issue #463: subscription name must not leak into a group chat confirmation
    var callback = buildCallback(1L, 100L, false, "FILTER:SEARCH");

    // When
    handler.handleSaveEdit(callback);

    // Then
    verify(wizard, never()).getState(any());
    verify(subscriptionService, never()).update(any(), any(), any());
  }

  @Test
  void should_send_session_expired_message_when_save_edit_has_no_wizard_state() {
    // Given
    when(wizard.getState(1L)).thenReturn(Optional.empty());
    var callback = buildCallback(1L, 100L, true, "FILTER:SEARCH");

    // When
    handler.handleSaveEdit(callback);

    // Then
    verify(subscriptionService, never()).update(any(), any(), any());
    verify(userService, never()).findByTelegramId(any());
  }

  @Test
  void should_send_not_found_message_when_edited_subscription_was_deleted() {
    // Given — subscription deleted between opening the edit wizard and saving
    var state = new SearchFilterState();
    state.setEditingSubscriptionId(5L);
    when(wizard.getState(1L)).thenReturn(Optional.of(state));
    when(editState.get(1L)).thenReturn(Optional.of(buildSubscriptionResponse(5L, "Test", true, DeliveryMode.REALTIME)));
    when(userService.findByTelegramId(1L)).thenReturn(Optional.of(buildUser(7L)));
    doThrow(new SubscriptionNotFoundException(5L)).when(subscriptionService).update(eq(7L), eq(5L), any());
    var callback = buildCallback(1L, 100L, true, "FILTER:SEARCH");

    // When / Then — no exception propagates, state is still cleared
    handler.handleSaveEdit(callback);
    verify(wizard).reset(1L);
    verify(editState).clear(1L);
  }

  // -------------------------------------------------------------------------
  // helpers
  // -------------------------------------------------------------------------

  private User buildUser(Long id) {
    var user = new User();
    user.setId(id);
    return user;
  }

  private SearchFilterState buildFilterState() {
    var state = new SearchFilterState();
    state.setDealType(DealType.RENT);
    state.setRooms(2);
    state.setPriceMin(BigDecimal.valueOf(500));
    state.setPriceMax(BigDecimal.valueOf(1500));
    return state;
  }

  private SubscriptionSearchCriteria buildSearchCriteria() {
    return new SubscriptionSearchCriteria(DealType.RENT, "APARTMENT", null, null, 1L,
        BigDecimal.valueOf(500), BigDecimal.valueOf(1500), 2, null, null);
  }

  private SubscriptionResponse buildSubscriptionResponse(Long id, String name, boolean active, DeliveryMode mode) {
    return new SubscriptionResponse(
        id, name, active, null, Set.of(), mode, null, null, null, null, Instant.now(), Instant.now()
    );
  }

  private SubscriptionResponse buildSubscriptionResponseWithCriteria(Long id, String name, boolean active,
      DeliveryMode mode, SubscriptionSearchCriteria criteria) {
    return new SubscriptionResponse(
        id, name, active, criteria, Set.of(), mode, null, null, null, null, Instant.now(), Instant.now()
    );
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
