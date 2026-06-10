package com.flatio.telegram.callback;

import com.flatio.telegram.keyboard.FilterKeyboardFactory;
import com.flatio.telegram.state.FilterStep;
import com.flatio.telegram.state.SearchFilterState;
import com.flatio.telegram.state.SearchFilterWizard;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FilterCallbackHandlerTest {

  @Mock
  private SearchFilterWizard wizard;

  @Mock
  private FilterKeyboardFactory keyboardFactory;

  @InjectMocks
  private FilterCallbackHandler handler;

  private SearchFilterState freshState;

  @BeforeEach
  void setUp() {
    freshState = new SearchFilterState();
    when(keyboardFactory.getStepText(any())).thenReturn("step text");
    when(keyboardFactory.buildForStep(any())).thenReturn(mock(InlineKeyboardMarkup.class));
  }

  @Test
  void should_start_wizard_when_action_search_received() {
    // Given
    when(wizard.start(1L)).thenReturn(freshState);
    var callback = buildCallback(1L, 100L, 10, "action:search");

    // When
    var result = handler.handle(callback);

    // Then
    verify(wizard).start(1L);
    assertEditMessage(result, "100", 10);
  }

  @Test
  void should_step_back_when_filter_back_received() {
    // Given
    when(wizard.stepBack(1L)).thenReturn(freshState);
    var callback = buildCallback(1L, 100L, 10, "FILTER:BACK");

    // When
    handler.handle(callback);

    // Then
    verify(wizard).stepBack(1L);
  }

  @Test
  void should_restart_wizard_when_filter_reset_received() {
    // Given
    when(wizard.start(1L)).thenReturn(freshState);
    var callback = buildCallback(1L, 100L, 10, "FILTER:RESET");

    // When
    handler.handle(callback);

    // Then
    verify(wizard).start(1L);
  }

  @Test
  void should_return_current_state_when_filter_search_received() {
    // Given
    when(wizard.getState(1L)).thenReturn(Optional.of(freshState));
    var callback = buildCallback(1L, 100L, 10, "FILTER:SEARCH");

    // When
    handler.handle(callback);

    // Then
    verify(wizard).getState(1L);
  }

  @Test
  void should_start_wizard_when_filter_search_received_but_no_state_exists() {
    // Given
    when(wizard.getState(1L)).thenReturn(Optional.empty());
    when(wizard.start(1L)).thenReturn(freshState);
    var callback = buildCallback(1L, 100L, 10, "FILTER:SEARCH");

    // When
    handler.handle(callback);

    // Then
    verify(wizard).start(1L);
  }

  @Test
  void should_apply_deal_type_selection_when_filter_step_value_received() {
    // Given
    when(wizard.applySelection(1L, FilterStep.DEAL_TYPE, "RENT")).thenReturn(freshState);
    var callback = buildCallback(1L, 100L, 10, "FILTER:DEAL_TYPE:RENT");

    // When
    handler.handle(callback);

    // Then
    verify(wizard).applySelection(1L, FilterStep.DEAL_TYPE, "RENT");
  }

  @Test
  void should_apply_property_type_selection_when_filter_property_type_received() {
    // Given
    when(wizard.applySelection(1L, FilterStep.PROPERTY_TYPE, "APARTMENT")).thenReturn(freshState);
    var callback = buildCallback(1L, 100L, 10, "FILTER:PROPERTY_TYPE:APARTMENT");

    // When
    handler.handle(callback);

    // Then
    verify(wizard).applySelection(1L, FilterStep.PROPERTY_TYPE, "APARTMENT");
  }

  @Test
  void should_apply_rooms_selection_when_filter_rooms_received() {
    // Given
    when(wizard.applySelection(1L, FilterStep.ROOMS, "2")).thenReturn(freshState);
    var callback = buildCallback(1L, 100L, 10, "FILTER:ROOMS:2");

    // When
    handler.handle(callback);

    // Then
    verify(wizard).applySelection(1L, FilterStep.ROOMS, "2");
  }

  @Test
  void should_fall_back_to_current_state_when_unknown_step_received() {
    // Given
    when(wizard.getState(1L)).thenReturn(Optional.of(freshState));
    var callback = buildCallback(1L, 100L, 10, "FILTER:NONEXISTENT_STEP:VALUE");

    // When
    handler.handle(callback);

    // Then
    verify(wizard).getState(1L);
  }

  @Test
  void should_return_edit_message_with_correct_chat_and_message_id() {
    // Given
    when(wizard.start(1L)).thenReturn(freshState);
    var callback = buildCallback(1L, 555L, 42, "action:search");

    // When
    var result = handler.handle(callback);

    // Then
    assertEditMessage(result, "555", 42);
  }

  private void assertEditMessage(EditMessageText result, String expectedChatId, int expectedMessageId) {
    assertThat(result).isNotNull();
    assertThat(result.getChatId()).isEqualTo(expectedChatId);
    assertThat(result.getMessageId()).isEqualTo(expectedMessageId);
  }

  private CallbackQuery buildCallback(long userId, long chatId, int messageId, String data) {
    var user = mock(User.class);
    when(user.getId()).thenReturn(userId);

    var message = mock(Message.class);
    when(message.getChatId()).thenReturn(chatId);
    when(message.getMessageId()).thenReturn(messageId);

    var callback = mock(CallbackQuery.class);
    when(callback.getFrom()).thenReturn(user);
    when(callback.getMessage()).thenReturn(message);
    when(callback.getData()).thenReturn(data);
    return callback;
  }
}
