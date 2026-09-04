package com.flatio.telegram.callback;

import com.flatio.telegram.keyboard.FilterKeyboardFactory;
import com.flatio.telegram.state.FilterStep;
import com.flatio.telegram.state.SearchFilterState;
import com.flatio.telegram.state.SearchFilterWizard;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.regex.Pattern;
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

  private static final String INVALID_INPUT_HINT = "Пожалуйста, воспользуйтесь кнопками ниже.";

  /** Matches "{min}-{max}", allowing decimal separators and surrounding whitespace (issue #526). */
  private static final Pattern PRICE_RANGE_PATTERN =
      Pattern.compile("^\\s*(\\d+(?:[.,]\\d+)?)\\s*-\\s*(\\d+(?:[.,]\\d+)?)\\s*$");
  private static final BigDecimal MAX_REASONABLE_PRICE = BigDecimal.valueOf(100_000_000);
  private static final String INVALID_PRICE_RANGE_TEXT =
      "Не удалось распознать диапазон. Введите два положительных числа через дефис, например "
      + "«1200-1800» (минимум не больше максимума), или воспользуйтесь кнопками выше.";

  private final SearchFilterWizard wizard;
  private final FilterKeyboardFactory keyboardFactory;

  private record PriceRange(BigDecimal min, BigDecimal max) {}

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
   * Starts the wizard pre-filled with a subscription's current criteria, for editing that
   * subscription (issue #479) rather than starting a plain search.
   *
   * @param telegramId Telegram user identifier, never null
   * @param chatId     target chat identifier, never null
   * @param prefilled  state pre-populated with the subscription's criteria and editing marker, never null
   * @return SendMessage displaying the first wizard step, never null
   */
  public SendMessage startWizardMessageForEdit(Long telegramId, String chatId, SearchFilterState prefilled) {
    var state = wizard.startForEdit(telegramId, prefilled);
    log.debug("Wizard started for subscription edit: telegramId={}, subscriptionId={}",
        telegramId, prefilled.getEditingSubscriptionId());
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
   * Checks whether the user has an active wizard session, regardless of step (issue #520).
   *
   * <p>Used to detect free text sent while the wizard is at a button-only step (e.g. "Тип
   * сделки") — every other step accepts input exclusively through inline keyboard callbacks, so
   * unrecognized text at those steps previously received no reply at all.
   *
   * @param telegramId Telegram user identifier, never null
   * @return true if the wizard has been started and not yet reset for this user
   */
  public boolean isWizardActive(Long telegramId) {
    return wizard.getState(telegramId).isPresent();
  }

  /**
   * Builds a reply for free text sent while the wizard is active but not at the KEYWORD step
   * (issue #520): a short hint plus the current step re-rendered, so the user is not left
   * wondering whether the bot received their message.
   *
   * @param telegramId Telegram user identifier, never null
   * @param chatId     target chat identifier, never null
   * @return SendMessage with the hint and the current wizard step, never null
   */
  public SendMessage handleInvalidFreeText(Long telegramId, String chatId) {
    var state = wizard.getState(telegramId).orElseGet(() -> wizard.start(telegramId));
    return SendMessage.builder()
        .chatId(chatId)
        .text(INVALID_INPUT_HINT + "\n\n" + keyboardFactory.getStepText(state))
        .parseMode("HTML")
        .replyMarkup(keyboardFactory.buildForStep(state))
        .build();
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

  /**
   * Checks whether the user's wizard is currently at the PRICE step, where free text is
   * interpreted as a custom range (issue #526).
   *
   * @param telegramId Telegram user identifier, never null
   * @return true if the wizard is at the PRICE step
   */
  public boolean isAtPriceStep(Long telegramId) {
    return wizard.getState(telegramId)
        .map(s -> s.getCurrentStep() == FilterStep.PRICE)
        .orElse(false);
  }

  /**
   * Applies free-text custom price range input and returns the next step (issue #526).
   *
   * <p>Called when the user types a message while the wizard is at the PRICE step, e.g.
   * {@code "1200-1800"}. Complements the preset buttons rather than replacing them. Invalid input
   * re-prompts at the same step instead of advancing, mirroring
   * {@link com.flatio.telegram.callback.BlacklistCallbackHandler}'s keyword re-prompt pattern.
   *
   * @param telegramId Telegram user identifier, never null
   * @param chatId     target chat identifier, never null
   * @param text       user-provided text, expected as {@code "{min}-{max}"}
   * @return SendMessage for the next step on success, or a re-prompt with an error on invalid input
   */
  public SendMessage handlePriceRangeText(Long telegramId, String chatId, String text) {
    var parsed = parsePriceRange(text);
    if (parsed.isEmpty()) {
      log.debug("Invalid custom price range input: telegramId={}, text={}", telegramId, text);
      return buildInvalidPriceRangeMessage(telegramId, chatId);
    }
    var range = parsed.get();
    var state = wizard.applyCustomPriceRange(telegramId, range.min(), range.max());
    return SendMessage.builder()
        .chatId(chatId)
        .text(keyboardFactory.getStepText(state))
        .parseMode("HTML")
        .replyMarkup(keyboardFactory.buildForStep(state))
        .build();
  }

  private Optional<PriceRange> parsePriceRange(String text) {
    if (text == null) {
      return Optional.empty();
    }
    var matcher = PRICE_RANGE_PATTERN.matcher(text.strip());
    if (!matcher.matches()) {
      return Optional.empty();
    }
    try {
      BigDecimal min = new BigDecimal(matcher.group(1).replace(',', '.'));
      BigDecimal max = new BigDecimal(matcher.group(2).replace(',', '.'));
      if (isValidRange(min, max)) {
        return Optional.of(new PriceRange(min, max));
      }
      return Optional.empty();
    } catch (NumberFormatException e) {
      return Optional.empty();
    }
  }

  private boolean isValidRange(BigDecimal min, BigDecimal max) {
    return min.signum() > 0 && max.signum() > 0
        && min.compareTo(max) <= 0
        && min.compareTo(MAX_REASONABLE_PRICE) <= 0
        && max.compareTo(MAX_REASONABLE_PRICE) <= 0;
  }

  private SendMessage buildInvalidPriceRangeMessage(Long telegramId, String chatId) {
    var state = wizard.getState(telegramId).orElseGet(() -> wizard.start(telegramId));
    return SendMessage.builder()
        .chatId(chatId)
        .text(INVALID_PRICE_RANGE_TEXT)
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
