package com.flatio.telegram.keyboard;

import com.flatio.telegram.handler.SearchResultSender;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

/**
 * Builds the inline keyboard for the bot's welcome / main menu message (issue #456).
 *
 * <p>Follows the same keyboard-builder pattern as {@link FilterKeyboardFactory} and
 * {@code com.flatio.telegram.formatter.ListingFormatter#buildKeyboard} — keyboard construction
 * lives here, not inline in the command/callback handler.
 */
@Component
public class MainMenuKeyboardFactory {

  /** Callback data value that opens the favorites section (issue #456). */
  public static final String ACTION_FAVORITES = "action:favorites";
  /** Callback data value that opens the subscriptions section (issue #456). */
  public static final String ACTION_SUBSCRIPTIONS = "action:subscriptions";
  /** Callback data value that opens the blacklist section (issue #456). */
  public static final String ACTION_BLACKLIST = "action:blacklist";

  private static final String ACTION_SEARCH = "action:search";
  private static final String ACTION_HELP = "action:help";

  /**
   * Builds the main menu keyboard.
   *
   * <p>First row keeps the original "Искать" / "Помощь" entry points (issue #418 predates this
   * change and must keep working). Second and third rows add the favorites/subscriptions/blacklist
   * entry points introduced by this issue.
   *
   * @return inline keyboard markup for the welcome/main menu, never null
   */
  public InlineKeyboardMarkup build() {
    var searchButton = btn("Искать", ACTION_SEARCH);
    var helpButton = btn("Помощь", ACTION_HELP);
    var favoritesButton = btn("⭐ Избранное", ACTION_FAVORITES);
    var subscriptionsButton = btn("🔔 Мои подписки", ACTION_SUBSCRIPTIONS);
    var blacklistButton = btn("🚫 Чёрный список", ACTION_BLACKLIST);

    return InlineKeyboardMarkup.builder()
        .keyboardRow(new InlineKeyboardRow(searchButton, helpButton))
        .keyboardRow(new InlineKeyboardRow(favoritesButton, subscriptionsButton))
        .keyboardRow(new InlineKeyboardRow(blacklistButton))
        .build();
  }

  /**
   * Builds a single-button "back to main menu" keyboard.
   *
   * <p>Used by the section handlers (favorites/subscriptions/blacklist) so the user can return to
   * the welcome menu without retyping {@code /start}.
   *
   * @return inline keyboard markup with one "🏠 Главное меню" button, never null
   */
  public InlineKeyboardMarkup buildBackToMenu() {
    var backButton = btn("🏠 Главное меню", SearchResultSender.ACTION_MENU);
    return InlineKeyboardMarkup.builder()
        .keyboardRow(new InlineKeyboardRow(backButton))
        .build();
  }

  private InlineKeyboardButton btn(String text, String callbackData) {
    return InlineKeyboardButton.builder().text(text).callbackData(callbackData).build();
  }
}
