package com.flatio.telegram.command;

import com.flatio.common.exception.ListingNotFoundException;
import com.flatio.service.ListingService;
import com.flatio.service.UserService;
import com.flatio.telegram.formatter.ListingFormatter;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

/**
 * Handles the {@code /start} Telegram command.
 *
 * <p>Registers the user on first interaction (or updates {@code lastSeen} on subsequent calls)
 * and replies with a welcome message containing action buttons.
 *
 * <p>Note: registration is always performed regardless of user intent — see OQ-25 for the
 * decision on mandatory vs optional registration.
 *
 * <p><b>Deep links (issue #418):</b> a {@code /start listing_<id>} payload — produced by a
 * {@code t.me/<bot_username>?start=listing_<id>} link — opens that listing's card directly
 * instead of the welcome message. Registration still happens first regardless of the payload,
 * since the link may be a brand-new user's first interaction with the bot.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class StartCommandHandler {

  private static final String DEEP_LINK_LISTING_PREFIX = "listing_";
  private static final String LISTING_UNAVAILABLE_TEXT =
      "Объявление не найдено. Возможно, ссылка устарела или объявление было удалено.";

  private final UserService userService;
  private final ListingService listingService;
  private final ListingFormatter listingFormatter;

  /**
   * Processes a {@code /start} update and returns either a listing card (deep link payload) or
   * the welcome reply.
   *
   * @param update Telegram update containing the /start command, never null
   * @return SendMessage with the resolved reply, never null
   */
  public SendMessage handle(Update update) {
    var from = update.getMessage().getFrom();
    Long telegramId = from.getId();
    String username = from.getUserName();
    String firstName = from.getFirstName();
    String chatId = String.valueOf(update.getMessage().getChatId());

    // TODO OQ-25: registration is always mandatory until OQ-25 is resolved
    userService.findOrCreate(telegramId, username, firstName);
    log.debug("Handled /start: telegramId={}, chatId={}", telegramId, chatId);

    String payload = extractStartPayload(update.getMessage().getText());
    if (payload != null && payload.startsWith(DEEP_LINK_LISTING_PREFIX)) {
      return buildListingDeepLinkMessage(chatId, payload.substring(DEEP_LINK_LISTING_PREFIX.length()));
    }
    return buildWelcomeMessage(chatId, firstName);
  }

  /**
   * Extracts the payload after {@code /start }, if any.
   *
   * @param text the full message text, e.g. {@code "/start listing_42"}, may be null
   * @return the payload (e.g. {@code "listing_42"}), or null if there is none
   */
  private String extractStartPayload(String text) {
    if (text == null) {
      return null;
    }
    String[] parts = text.trim().split("\\s+", 2);
    return parts.length == 2 ? parts[1] : null;
  }

  /**
   * Resolves a listing deep link into its card, or a graceful "not found" reply for a malformed
   * or unknown ID — never propagates an exception back to the caller.
   *
   * @param chatId target chat identifier, never null
   * @param idPart the payload after {@value #DEEP_LINK_LISTING_PREFIX}, e.g. {@code "42"}
   * @return SendMessage with the listing card or an unavailable notice, never null
   */
  private SendMessage buildListingDeepLinkMessage(String chatId, String idPart) {
    Long listingId = parseListingId(idPart);
    if (listingId == null) {
      log.debug("Ignoring malformed listing deep link payload: {}", idPart);
      return buildListingUnavailableMessage(chatId);
    }
    try {
      var listing = listingService.findById(listingId, null);
      return SendMessage.builder()
          .chatId(chatId)
          .text(listingFormatter.buildDeepLinkCaption(listing))
          .parseMode("HTML")
          .replyMarkup(listingFormatter.buildKeyboard(listing.sourceUrl()))
          .build();
    } catch (ListingNotFoundException e) {
      log.debug("Deep link referenced unknown listing: listingId={}", listingId);
      return buildListingUnavailableMessage(chatId);
    }
  }

  private Long parseListingId(String idPart) {
    try {
      return Long.valueOf(idPart);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private SendMessage buildListingUnavailableMessage(String chatId) {
    return SendMessage.builder()
        .chatId(chatId)
        .text(LISTING_UNAVAILABLE_TEXT)
        .build();
  }

  /**
   * Builds the main menu message for a callback context where no user name is available.
   *
   * @param chatId target chat identifier, never null
   * @return SendMessage with main menu keyboard, never null
   */
  public SendMessage buildMenuMessage(String chatId) {
    return buildWelcomeMessage(chatId, null);
  }

  private SendMessage buildWelcomeMessage(String chatId, String firstName) {
    String greeting = firstName != null && !firstName.isBlank()
        ? "Привет, " + firstName + "!"
        : "Привет!";

    var searchButton = InlineKeyboardButton.builder()
        .text("Искать")
        .callbackData("action:search")
        .build();
    var helpButton = InlineKeyboardButton.builder()
        .text("Помощь")
        .callbackData("action:help")
        .build();

    var keyboard = InlineKeyboardMarkup.builder()
        .keyboardRow(new InlineKeyboardRow(searchButton, helpButton))
        .build();

    return SendMessage.builder()
        .chatId(chatId)
        .text(greeting + "\n\nДобро пожаловать в Flatio — агрегатор объявлений о недвижимости.")
        .replyMarkup(keyboard)
        .build();
  }
}
