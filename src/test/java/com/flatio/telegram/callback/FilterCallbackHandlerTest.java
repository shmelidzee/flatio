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
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
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
    // lenient: not all tests invoke handle() or handleKeywordText(), so these stubs may be unused
    lenient().when(keyboardFactory.getStepText(any())).thenReturn("step text");
    lenient().when(keyboardFactory.buildForStep(any())).thenReturn(mock(InlineKeyboardMarkup.class));
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

  // -------------------------------------------------------------------------
  // parseMode(HTML) — required for escapeHtml()-encoded keyword text to render
  // correctly instead of as literal entities (issue #384)
  // -------------------------------------------------------------------------

  @Test
  void should_set_html_parse_mode_when_handling_callback() {
    // Given
    when(wizard.start(1L)).thenReturn(freshState);
    var callback = buildCallback(1L, 100L, 10, "action:search");

    // When
    var result = handler.handle(callback);

    // Then
    assertThat(result.getParseMode()).isEqualTo("HTML");
  }

  @Test
  void should_set_html_parse_mode_when_starting_wizard_message() {
    // Given
    when(wizard.start(1L)).thenReturn(freshState);

    // When
    var result = handler.startWizardMessage(1L, "100");

    // Then
    assertThat(result.getParseMode()).isEqualTo("HTML");
  }

  @Test
  void should_set_html_parse_mode_when_handling_keyword_text() {
    // Given
    when(wizard.applyKeyword(1L, "гараж & сарай")).thenReturn(freshState);

    // When
    var result = handler.handleKeywordText(1L, "100", "гараж & сарай");

    // Then
    assertThat(result.getParseMode()).isEqualTo("HTML");
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

  // -------------------------------------------------------------------------
  // handleKeywordText — free-text keyword input routing (#305)
  // -------------------------------------------------------------------------

  @Test
  void should_apply_multi_word_keyword_and_return_done_step_message() {
    // Given
    var doneState = new SearchFilterState();
    doneState.setCurrentStep(FilterStep.DONE);
    doneState.setQuery("тихий двор Минск");
    when(wizard.applyKeyword(1L, "тихий двор Минск")).thenReturn(doneState);

    // When
    var result = handler.handleKeywordText(1L, "100", "тихий двор Минск");

    // Then — wizard receives the full multi-word string; result is a SendMessage for the DONE step
    verify(wizard).applyKeyword(1L, "тихий двор Минск");
    assertThat(result).isNotNull().isInstanceOf(SendMessage.class);
    assertThat(result.getChatId()).isEqualTo("100");
  }

  // -------------------------------------------------------------------------
  // isAtKeywordStep — routing guard in FlatioBot (#305)
  // -------------------------------------------------------------------------

  @Test
  void should_return_true_when_wizard_is_at_keyword_step() {
    // Given
    var state = new SearchFilterState();
    state.setCurrentStep(FilterStep.KEYWORD);
    when(wizard.getState(1L)).thenReturn(Optional.of(state));

    // When / Then
    assertThat(handler.isAtKeywordStep(1L)).isTrue();
  }

  @Test
  void should_return_false_when_wizard_is_not_at_keyword_step() {
    // Given
    var state = new SearchFilterState();
    state.setCurrentStep(FilterStep.DEAL_TYPE);
    when(wizard.getState(1L)).thenReturn(Optional.of(state));

    // When / Then
    assertThat(handler.isAtKeywordStep(1L)).isFalse();
  }

  @Test
  void should_return_false_when_no_wizard_state_exists() {
    // Given
    when(wizard.getState(1L)).thenReturn(Optional.empty());

    // When / Then
    assertThat(handler.isAtKeywordStep(1L)).isFalse();
  }

  // -------------------------------------------------------------------------
  // isWizardActive / handleInvalidFreeText — free text at a button-only step (#520)
  // -------------------------------------------------------------------------

  @Test
  void should_return_true_when_wizard_has_active_state_regardless_of_step() {
    // Given — active at a button-only step, not KEYWORD
    var state = new SearchFilterState();
    state.setCurrentStep(FilterStep.DEAL_TYPE);
    when(wizard.getState(1L)).thenReturn(Optional.of(state));

    // When / Then
    assertThat(handler.isWizardActive(1L)).isTrue();
  }

  @Test
  void should_return_false_from_is_wizard_active_when_no_state_exists() {
    // Given
    when(wizard.getState(1L)).thenReturn(Optional.empty());

    // When / Then
    assertThat(handler.isWizardActive(1L)).isFalse();
  }

  @Test
  void should_include_hint_and_current_step_when_handling_invalid_free_text() {
    // Given
    var state = new SearchFilterState();
    state.setCurrentStep(FilterStep.PROPERTY_TYPE);
    when(wizard.getState(1L)).thenReturn(Optional.of(state));
    when(keyboardFactory.getStepText(state)).thenReturn("Тип недвижимости:");

    // When
    var result = handler.handleInvalidFreeText(1L, "100");

    // Then
    assertThat(result.getChatId()).isEqualTo("100");
    assertThat(result.getParseMode()).isEqualTo("HTML");
    assertThat(result.getText())
        .contains("Пожалуйста, воспользуйтесь кнопками ниже.")
        .contains("Тип недвижимости:");
  }

  @Test
  void should_start_wizard_when_handling_invalid_free_text_but_no_state_exists() {
    // Given — defensive fallback: should not normally happen since FlatioBot only calls this
    // when isWizardActive() was already true, but the handler must not throw either way
    when(wizard.getState(1L)).thenReturn(Optional.empty());
    when(wizard.start(1L)).thenReturn(freshState);

    // When
    var result = handler.handleInvalidFreeText(1L, "100");

    // Then
    verify(wizard).start(1L);
    assertThat(result).isNotNull();
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
