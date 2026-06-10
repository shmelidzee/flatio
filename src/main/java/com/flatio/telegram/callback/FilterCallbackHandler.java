package com.flatio.telegram.callback;

import com.flatio.telegram.keyboard.FilterKeyboardFactory;
import com.flatio.telegram.state.FilterStep;
import com.flatio.telegram.state.SearchFilterState;
import com.flatio.telegram.state.SearchFilterWizard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
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
        .replyMarkup(keyboardFactory.buildForStep(state))
        .build();
  }

  private SearchFilterState resolveState(Long telegramId, String data) {
    if ("action:search".equals(data)) {
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
