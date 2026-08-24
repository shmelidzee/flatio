package com.flatio.telegram.callback;

import com.flatio.telegram.keyboard.FilterKeyboardFactory;
import com.flatio.telegram.state.FilterStep;
import com.flatio.telegram.state.SearchFilterState;
import com.flatio.telegram.state.SearchFilterWizard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;

/**
 * Handles inline keyboard callbacks for the search filter wizard.
 *
 * <p>Processes {@code action:search} (starts the wizard) and all
 * {@code FILTER:*} callbacks (step selection, back, reset, search trigger).
 * Returns an {@link EditMessageText} that replaces the original wizard message
 * with the next step.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class FilterCallbackHandler {

  /** Callback data value that triggers the start of the filter wizard. */
  public static final String ACTION_SEARCH = "action:search";

  private final SearchFilterWizard wizard;
  private final FilterKeyboardFactory keyboardFactory;

  /**
   * Processes a filter callback and returns the updated wizard message.
   *
   * @param callbackQuery the incoming callback query, never null
   * @return EditMessageText to replace the current message with the next step, never null
   */
  public EditMessageText handle(CallbackQuery callbackQuery) {
    Long telegramId = callbackQuery.getFrom().getId();
    String chatId = String.valueOf(callbackQuery.getMessage().getChatId());
    Integer messageId = callbackQuery.getMessage().getMessageId();
    String data = callbackQuery.getData();

    log.debug("Filter callback: telegramId={}, data={}", telegramId, data);

    var state = resolveState(telegramId, data);

    return EditMessageText.builder()
        .chatId(chatId)
        .messageId(messageId)
        .text(keyboardFactory.getStepText(state))
        .parseMode("HTML")
        .replyMarkup(keyboardFactory.buildForStep(state))
        .build();
  }

  /**
   * Starts the wizard for a {@code /search} command (text message context).
   *
   * <p>Returns a {@link SendMessage} rather than {@link EditMessageText} because
   * text commands have no existing wizard message to edit.
   *
   * @param telegramId Telegram user identifier, never null
   * @param chatId     target chat identifier, never null
   * @return SendMessage displaying the first wizard step, never null
   */
  public SendMessage startWizardMessage(Long telegramId, String chatId) {
    var state = wizard.start(telegramId);
    log.debug("Wizard started via /search: telegramId={}", telegramId);
    return SendMessage.builder()
        .chatId(chatId)
        .text(keyboardFactory.getStepText(state))
        .parseMode("HTML")
        .replyMarkup(keyboardFactory.buildForStep(state))
        .build();
  }

  /**
   * Checks whether the user's wizard is currently waiting for keyword text input.
   *
   * @param telegramId Telegram user identifier, never null
   * @return true if the wizard is at the KEYWORD step
   */
  public boolean isAtKeywordStep(Long telegramId) {
    return wizard.getState(telegramId)
        .map(s -> s.getCurrentStep() == FilterStep.KEYWORD)
        .orElse(false);
  }

  /**
   * Applies free-text keyword input and returns the DONE step as a new message.
   *
   * <p>Called when the user types a message while the wizard is at the KEYWORD step.
   * Returns a {@link SendMessage} because the incoming update is a text message,
   * not a callback — there is no existing wizard message to edit.
   *
   * @param telegramId Telegram user identifier, never null
   * @param chatId     target chat identifier, never null
   * @param text       user-provided keyword text, may be blank (treated as "skip")
   * @return SendMessage displaying the DONE wizard step with search button, never null
   */
  public SendMessage handleKeywordText(Long telegramId, String chatId, String text) {
    var state = wizard.applyKeyword(telegramId, text);
    log.debug("Keyword text applied: telegramId={}, text={}", telegramId, text);
    return SendMessage.builder()
        .chatId(chatId)
        .text(keyboardFactory.getStepText(state))
        .parseMode("HTML")
        .replyMarkup(keyboardFactory.buildForStep(state))
        .build();
  }

  private SearchFilterState resolveState(Long telegramId, String data) {
    if (ACTION_SEARCH.equals(data)) {
      return wizard.start(telegramId);
    }
    if ((SearchFilterWizard.CALLBACK_PREFIX + ":BACK").equals(data)) {
      return wizard.stepBack(telegramId);
    }
    if ((SearchFilterWizard.CALLBACK_PREFIX + ":RESET").equals(data)) {
      return wizard.start(telegramId);
    }
    if ((SearchFilterWizard.CALLBACK_PREFIX + ":SEARCH").equals(data)) {
      return wizard.getState(telegramId).orElseGet(() -> wizard.start(telegramId));
    }
    return applyStepValue(telegramId, data);
  }

  private SearchFilterState applyStepValue(Long telegramId, String data) {
    String[] parts = data.split(":", 3);
    if (parts.length == 3) {
      try {
        var step = FilterStep.valueOf(parts[1]);
        return wizard.applySelection(telegramId, step, parts[2]);
      } catch (IllegalArgumentException e) {
        log.warn("Unknown filter step in callback: data={}", data);
      }
    }
    return wizard.getState(telegramId).orElseGet(() -> wizard.start(telegramId));
  }
}
