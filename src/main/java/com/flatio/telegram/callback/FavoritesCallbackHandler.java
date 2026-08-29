package com.flatio.telegram.callback;

import com.flatio.common.exception.FavoriteLimitExceededException;
import com.flatio.common.exception.FavoriteNotFoundException;
import com.flatio.common.exception.ListingNotFoundException;
import com.flatio.common.util.TelegramHtmlEscaper;
import com.flatio.common.util.TelegramPrivateChatGuard;
import com.flatio.service.FavoriteService;
import com.flatio.service.UserService;
import com.flatio.telegram.handler.SearchResultSender;
import com.flatio.telegram.keyboard.MainMenuKeyboardFactory;
import com.flatio.web.dto.CreateFavoriteRequest;
import com.flatio.web.dto.FavoriteResponse;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

/**
 * Handles the "⭐ Избранное" section: viewing the paginated favorites list and toggling favorites
 * from both that list and search result cards (issue #457).
 *
 * <p>Reuses {@link FavoriteService}, the same service backing the REST {@code /api/v1/favorites}
 * endpoint (Telegram and REST API share services, per project architecture rules). Unlike the
 * read-only summary shipped in #456, this renders one Telegram message per favorite (with its own
 * "remove" button) followed by a single pagination/navigation message, matching the shape already
 * used for search results by {@link SearchResultSender}.
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
  /** Callback prefix for every callback this handler owns. */
  public static final String CALLBACK_PREFIX = "FAV:";
  /** Callback prefix for favorites-list pagination. */
  public static final String PAGE_PREFIX = "FAV:PAGE:";
  /** Callback data for advancing to the next favorites page. */
  public static final String PAGE_NEXT = "FAV:PAGE:NEXT";
  /** Callback data for going back to the previous favorites page. */
  public static final String PAGE_PREV = "FAV:PAGE:PREV";
  /** Callback prefix for adding a listing to favorites from a search result card. */
  public static final String ADD_PREFIX = "FAV:ADD:";
  /** Callback prefix for removing a listing from favorites from the favorites list. */
  public static final String REMOVE_PREFIX = "FAV:REMOVE:";

  private static final int PAGE_SIZE = 5;
  private static final long SESSION_TTL_MINUTES = 30;
  private static final long MAX_SESSIONS = 10_000;

  private static final String EMPTY_TEXT = "⭐ У вас пока нет избранных объявлений."
      + "\n\nДобавьте объявление в избранное с его карточки в поиске.";
  private static final String SESSION_EXPIRED_TEXT = "Список устарел. Откройте раздел «⭐ Избранное» заново.";
  private static final String UNREGISTERED_TOAST = "Сначала запустите бота командой /start.";
  private static final String ADDED_TOAST = "⭐ Добавлено в избранное";
  private static final String REMOVED_TOAST = "Убрано из избранного";
  private static final String LIMIT_EXCEEDED_TOAST =
      "Достигнут лимит избранного по вашему тарифу. Уберите что-то из списка, чтобы добавить новое.";
  private static final String LISTING_NOT_FOUND_TOAST = "Это объявление больше недоступно.";

  private final UserService userService;
  private final FavoriteService favoriteService;
  private final MainMenuKeyboardFactory keyboardFactory;
  private final TelegramClient telegramClient;

  private record PageState(int page, int totalPages) {}

  // Caffeine, not a plain map, so an abandoned pagination session is evicted instead of occupying
  // memory for the lifetime of the JVM — same rationale as SearchResultSender#sessions.
  private final Map<Long, PageState> pageSessions = Caffeine.newBuilder()
      .expireAfterAccess(Duration.ofMinutes(SESSION_TTL_MINUTES))
      .maximumSize(MAX_SESSIONS)
      .<Long, PageState>build()
      .asMap();

  /**
   * Renders the first page of the user's favorites list.
   *
   * @param callbackQuery the incoming callback query, never null
   */
  public void handle(CallbackQuery callbackQuery) {
    renderPage(callbackQuery, 0);
  }

  /**
   * Handles a {@code FAV:PAGE:NEXT}/{@code FAV:PAGE:PREV} pagination callback.
   *
   * @param callbackQuery the incoming callback query, never null
   */
  public void handlePage(CallbackQuery callbackQuery) {
    Long telegramId = callbackQuery.getFrom().getId();
    String chatId = String.valueOf(callbackQuery.getMessage().getChatId());
    var session = pageSessions.get(telegramId);
    if (session == null) {
      sendText(chatId, SESSION_EXPIRED_TEXT);
      return;
    }
    int next = PAGE_NEXT.equals(callbackQuery.getData())
        ? Math.min(session.page() + 1, session.totalPages() - 1)
        : Math.max(session.page() - 1, 0);
    renderPage(callbackQuery, next);
  }

  /**
   * Handles a {@code FAV:ADD:<listingId>} callback from a search result card.
   *
   * @param callbackQuery the incoming callback query, never null
   * @return toast text to show the user via {@code AnswerCallbackQuery}, never null
   */
  public String handleAdd(CallbackQuery callbackQuery) {
    if (!TelegramPrivateChatGuard.isPrivateChat(callbackQuery)) {
      return TelegramPrivateChatGuard.PRIVATE_CHAT_REQUIRED_TEXT;
    }
    var userOpt = userService.findByTelegramId(callbackQuery.getFrom().getId());
    if (userOpt.isEmpty()) {
      return UNREGISTERED_TOAST;
    }
    Long listingId = parseId(callbackQuery.getData(), ADD_PREFIX);
    if (listingId == null) {
      return LISTING_NOT_FOUND_TOAST;
    }
    try {
      favoriteService.create(userOpt.get().getId(), new CreateFavoriteRequest(listingId));
      return ADDED_TOAST;
    } catch (FavoriteLimitExceededException e) {
      return LIMIT_EXCEEDED_TOAST;
    } catch (ListingNotFoundException e) {
      return LISTING_NOT_FOUND_TOAST;
    }
  }

  /**
   * Handles a {@code FAV:REMOVE:<listingId>} callback from the favorites list.
   *
   * @param callbackQuery the incoming callback query, never null
   * @return toast text to show the user via {@code AnswerCallbackQuery}, never null
   */
  public String handleRemove(CallbackQuery callbackQuery) {
    if (!TelegramPrivateChatGuard.isPrivateChat(callbackQuery)) {
      return TelegramPrivateChatGuard.PRIVATE_CHAT_REQUIRED_TEXT;
    }
    var userOpt = userService.findByTelegramId(callbackQuery.getFrom().getId());
    if (userOpt.isEmpty()) {
      return UNREGISTERED_TOAST;
    }
    Long listingId = parseId(callbackQuery.getData(), REMOVE_PREFIX);
    if (listingId == null) {
      return LISTING_NOT_FOUND_TOAST;
    }
    try {
      favoriteService.delete(userOpt.get().getId(), listingId);
    } catch (FavoriteNotFoundException e) {
      log.debug("FAV:REMOVE for already-removed favorite: listingId={}", listingId);
    }
    return REMOVED_TOAST;
  }

  private void renderPage(CallbackQuery callbackQuery, int page) {
    Long telegramId = callbackQuery.getFrom().getId();
    String chatId = String.valueOf(callbackQuery.getMessage().getChatId());

    if (!TelegramPrivateChatGuard.isPrivateChat(callbackQuery)) {
      log.debug("FAV callback rejected outside a private chat: chatId={}", chatId);
      sendText(chatId, TelegramPrivateChatGuard.PRIVATE_CHAT_REQUIRED_TEXT);
      return;
    }

    var userOpt = userService.findByTelegramId(telegramId);
    if (userOpt.isEmpty()) {
      log.warn("FAV callback from unregistered telegramId={}", telegramId);
      sendText(chatId, EMPTY_TEXT);
      return;
    }

    var pageable = PageRequest.of(page, PAGE_SIZE, Sort.by(Sort.Direction.DESC, "createdAt"));
    var result = favoriteService.findByUser(userOpt.get().getId(), pageable);
    if (result.isEmpty()) {
      pageSessions.remove(telegramId);
      sendEmptyState(chatId);
      return;
    }

    pageSessions.put(telegramId, new PageState(page, result.getTotalPages()));
    log.debug("Favorites page rendered: telegramId={}, page={}, totalPages={}",
        telegramId, page, result.getTotalPages());
    sendItems(chatId, result.getContent());
    sendNavigation(chatId, page, result.getTotalPages());
  }

  private void sendItems(String chatId, List<FavoriteResponse> items) {
    for (var item : items) {
      try {
        sendItem(chatId, item);
      } catch (Exception e) {
        log.error("Unexpected error sending favorite item: favoriteId={}", item.id(), e);
      }
    }
  }

  private void sendItem(String chatId, FavoriteResponse item) {
    var removeButton = InlineKeyboardButton.builder()
        .text("❌ Убрать из избранного")
        .callbackData(REMOVE_PREFIX + item.listing().id())
        .build();
    var keyboard = InlineKeyboardMarkup.builder()
        .keyboardRow(new InlineKeyboardRow(removeButton))
        .build();
    try {
      telegramClient.execute(SendMessage.builder()
          .chatId(chatId)
          .text(formatItem(item))
          .parseMode("HTML")
          .replyMarkup(keyboard)
          .build());
    } catch (TelegramApiException e) {
      log.warn("Failed to send favorite item: chatId={}", chatId, e);
    }
  }

  private String formatItem(FavoriteResponse item) {
    var sb = new StringBuilder();
    sb.append(TelegramHtmlEscaper.escapeHtml(item.listing().title())).append(" — ").append(formatPrice(item));
    if (item.listingInactive()) {
      sb.append("\n❗️Объявление неактуально");
    } else if (item.priceChanged()) {
      sb.append("\n💱 Цена изменилась: ").append(formatDelta(item));
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

  private String formatDelta(FavoriteResponse item) {
    BigDecimal delta = item.priceDelta();
    if (delta == null) {
      return "";
    }
    String sign = delta.signum() > 0 ? "+" : "";
    String currency = item.listing().currency() != null ? item.listing().currency() : "";
    return sign + delta.stripTrailingZeros().toPlainString() + " " + currency;
  }

  private void sendNavigation(String chatId, int page, int totalPages) {
    var navButtons = new ArrayList<InlineKeyboardButton>();
    if (page > 0) {
      navButtons.add(navBtn("← Предыдущие", PAGE_PREV));
    }
    if (page < totalPages - 1) {
      navButtons.add(navBtn("Ещё →", PAGE_NEXT));
    }
    var markupBuilder = InlineKeyboardMarkup.builder();
    if (!navButtons.isEmpty()) {
      markupBuilder.keyboardRow(new InlineKeyboardRow(navButtons));
    }
    markupBuilder.keyboardRow(new InlineKeyboardRow(navBtn("🏠 Главное меню", SearchResultSender.ACTION_MENU)));

    try {
      telegramClient.execute(SendMessage.builder()
          .chatId(chatId)
          .text("📄 Страница " + (page + 1) + " из " + totalPages)
          .replyMarkup(markupBuilder.build())
          .build());
    } catch (TelegramApiException e) {
      log.warn("Failed to send favorites navigation: chatId={}", chatId, e);
    }
  }

  private void sendText(String chatId, String text) {
    try {
      telegramClient.execute(SendMessage.builder()
          .chatId(chatId)
          .text(text)
          .parseMode("HTML")
          .replyMarkup(keyboardFactory.buildBackToMenu())
          .build());
    } catch (TelegramApiException e) {
      log.warn("Failed to send favorites message: chatId={}", chatId, e);
    }
  }

  /**
   * Sends the empty-favorites message with a shortcut into the search wizard, so the user is not
   * left at a dead end without knowing how to add a first favorite (issue #474).
   *
   * @param chatId target chat identifier, never null
   */
  private void sendEmptyState(String chatId) {
    var keyboard = InlineKeyboardMarkup.builder()
        .keyboardRow(new InlineKeyboardRow(navBtn("🔍 Перейти к поиску", FilterCallbackHandler.ACTION_SEARCH)))
        .keyboardRow(new InlineKeyboardRow(navBtn("🏠 Главное меню", SearchResultSender.ACTION_MENU)))
        .build();
    try {
      telegramClient.execute(SendMessage.builder()
          .chatId(chatId)
          .text(EMPTY_TEXT)
          .parseMode("HTML")
          .replyMarkup(keyboard)
          .build());
    } catch (TelegramApiException e) {
      log.warn("Failed to send favorites empty state: chatId={}", chatId, e);
    }
  }

  private InlineKeyboardButton navBtn(String text, String callbackData) {
    return InlineKeyboardButton.builder().text(text).callbackData(callbackData).build();
  }

  private Long parseId(String data, String prefix) {
    try {
      return Long.valueOf(data.substring(prefix.length()));
    } catch (RuntimeException e) {
      log.warn("Malformed favorites callback data: {}", data);
      return null;
    }
  }
}
