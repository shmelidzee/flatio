package com.flatio.telegram.callback;

import com.flatio.common.util.TelegramHtmlEscaper;
import com.flatio.common.util.TelegramPrivateChatGuard;
import com.flatio.service.FavoriteService;
import com.flatio.service.UserService;
import com.flatio.telegram.keyboard.MainMenuKeyboardFactory;
import com.flatio.web.dto.FavoriteResponse;
import java.math.BigDecimal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;

/**
 * Handles the {@code action:favorites} callback — opens the "Избранное" section (issue #456).
 *
 * <p>Reuses {@link FavoriteService}, the same service backing the REST {@code /api/v1/favorites}
 * endpoint (Telegram and REST API share services, per project architecture rules). Shows a
 * read-only summary of the most recently added favorites; per-item management (remove, open card)
 * is out of scope for this issue and left to a follow-up (#TBD-FAV).
 *
 * <p>Restricted to private chats (issue #463): favorites are personal data, so a request made
 * from a group/supergroup/channel is answered with a redirect to a private chat instead of the
 * actual list — see {@link TelegramPrivateChatGuard}.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class FavoritesCallbackHandler {

  /** Callback data value that opens the favorites section. */
  public static final String ACTION_FAVORITES = MainMenuKeyboardFactory.ACTION_FAVORITES;

  private static final int PAGE_SIZE = 10;
  private static final String HEADER = "⭐ <b>Избранное</b>\n\n";
  private static final String EMPTY_TEXT = "⭐ У вас пока нет избранных объявлений.";

  private final UserService userService;
  private final FavoriteService favoriteService;
  private final MainMenuKeyboardFactory keyboardFactory;

  /**
   * Builds the favorites list reply for the given callback.
   *
   * @param callbackQuery the incoming callback query, never null
   * @return SendMessage listing the user's favorites, never null
   */
  public SendMessage handle(CallbackQuery callbackQuery) {
    Long telegramId = callbackQuery.getFrom().getId();
    String chatId = String.valueOf(callbackQuery.getMessage().getChatId());

    if (!TelegramPrivateChatGuard.isPrivateChat(callbackQuery)) {
      log.debug("action:favorites callback rejected outside a private chat: chatId={}", chatId);
      return buildMessage(chatId, TelegramPrivateChatGuard.PRIVATE_CHAT_REQUIRED_TEXT);
    }

    var user = userService.findByTelegramId(telegramId);
    if (user.isEmpty()) {
      log.warn("action:favorites callback from unregistered telegramId={}", telegramId);
      return buildMessage(chatId, EMPTY_TEXT);
    }

    var pageable = PageRequest.of(0, PAGE_SIZE, Sort.by(Sort.Direction.DESC, "createdAt"));
    var page = favoriteService.findByUser(user.get().getId(), pageable);
    log.debug("Favorites list requested: telegramId={}, count={}", telegramId, page.getNumberOfElements());

    String text = page.isEmpty() ? EMPTY_TEXT : HEADER + formatItems(page.getContent());
    return buildMessage(chatId, text);
  }

  private String formatItems(List<FavoriteResponse> items) {
    var sb = new StringBuilder();
    int index = 1;
    for (var item : items) {
      sb.append(index++).append(". ").append(TelegramHtmlEscaper.escapeHtml(item.listing().title()))
          .append(" — ").append(formatPrice(item)).append("\n");
    }
    return sb.toString();
  }

  private String formatPrice(FavoriteResponse item) {
    BigDecimal price = item.currentPrice() != null ? item.currentPrice() : item.listing().price();
    if (price == null) {
      return "";
    }
    String currency = item.listing().currency() != null ? item.listing().currency() : "";
    return price.stripTrailingZeros().toPlainString() + " " + currency;
  }

  private SendMessage buildMessage(String chatId, String text) {
    return SendMessage.builder()
        .chatId(chatId)
        .text(text)
        .parseMode("HTML")
        .replyMarkup(keyboardFactory.buildBackToMenu())
        .build();
  }
}
