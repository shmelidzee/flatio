package com.flatio.telegram.command;

import com.flatio.service.UserSavedSearchService;
import com.flatio.telegram.callback.FilterCallbackHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

/**
 * Handles the {@code /search} command.
 *
 * <p>When the user has a saved filter, offers a choice between reusing it and starting a
 * fresh wizard. When no saved filter exists, starts the wizard immediately.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class SearchCommandHandler {

  /** Callback data sent when the user chooses to reuse their last saved search. */
  public static final String ACTION_USE_LAST_SEARCH = "action:use-last-search";

  private static final String CHOICE_TEXT =
      "У вас есть сохранённый поиск. Хотите использовать его или начать новый?";

  private final UserSavedSearchService userSavedSearchService;
  private final FilterCallbackHandler filterCallbackHandler;

  /**
   * Processes the {@code /search} command.
   *
   * <p>If the user has a saved filter, returns a message with two buttons: reuse last search
   * or start a new one. Otherwise starts the filter wizard directly.
   *
   * @param telegramId Telegram user identifier, never null
   * @param chatId     target chat identifier, never null
   * @return SendMessage to deliver to the user, never null
   */
  public SendMessage handle(Long telegramId, String chatId) {
    boolean hasSavedFilter = userSavedSearchService.getByTelegramUserId(telegramId).isPresent();
    if (!hasSavedFilter) {
      log.debug("No saved filter, starting wizard: telegramId={}", telegramId);
      return filterCallbackHandler.startWizardMessage(telegramId, chatId);
    }
    log.debug("Saved filter found, showing choice: telegramId={}", telegramId);
    return SendMessage.builder()
        .chatId(chatId)
        .text(CHOICE_TEXT)
        .replyMarkup(buildChoiceKeyboard())
        .build();
  }

  private InlineKeyboardMarkup buildChoiceKeyboard() {
    var reuseBtn = InlineKeyboardButton.builder()
        .text("🔁 Использовать прошлый поиск")
        .callbackData(ACTION_USE_LAST_SEARCH)
        .build();
    var newBtn = InlineKeyboardButton.builder()
        .text("🔍 Новый поиск")
        .callbackData(FilterCallbackHandler.ACTION_SEARCH)
        .build();
    return InlineKeyboardMarkup.builder()
        .keyboardRow(new InlineKeyboardRow(reuseBtn))
        .keyboardRow(new InlineKeyboardRow(newBtn))
        .build();
  }
}
