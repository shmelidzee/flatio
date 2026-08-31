package com.flatio.telegram.callback;

import com.flatio.common.exception.FavoriteLimitExceededException;
import com.flatio.common.exception.FavoriteNotFoundException;
import com.flatio.common.exception.ListingNotFoundException;
import com.flatio.common.util.TelegramPrivateChatGuard;
import com.flatio.service.FavoriteService;
import com.flatio.service.UserService;
import com.flatio.telegram.formatter.ListingFormatter;
import com.flatio.telegram.handler.PhotoProxyClient;
import com.flatio.telegram.handler.SearchResultSender;
import com.flatio.telegram.keyboard.MainMenuKeyboardFactory;
import com.flatio.web.dto.CreateFavoriteRequest;
import com.flatio.web.dto.FavoriteResponse;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.InputFile;
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
 * endpoint (Telegram and REST API share services, per project architecture rules). A page of
 * favorites renders as one photo card per favorite — title, price and status caption, its photo
 * (or a placeholder), an "open listing" link button and a "remove" button — followed by a shared
 * pagination/menu message, mirroring how {@link SearchResultSender} renders search result cards
 * (issue #494: favorites were previously a single text-only message per page, inconsistent with
 * search cards). Page size stays {@value #PAGE_SIZE}, the same order of magnitude as search's
 * per-page card count, so this does not introduce a new flood risk (issue #476 already established
 * that bound for this section).
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
  private static final String ITEM_FORMAT_ERROR_TEXT = "⚠️ Не удалось отобразить это объявление.";
  private static final String LABEL_LISTING_INACTIVE = "❗️Объявление неактуально";
  private static final String UNREGISTERED_TOAST = "Сначала запустите бота командой /start.";
  private static final String ADDED_TOAST = "⭐ Добавлено в избранное";
  private static final String REMOVED_TOAST = "Убрано из избранного";
  private static final String LIMIT_EXCEEDED_TOAST =
      "Достигнут лимит избранного по вашему тарифу. Уберите что-то из списка, чтобы добавить новое.";
  private static final String LISTING_NOT_FOUND_TOAST = "Это объявление больше недоступно.";

  @Value("${telegram.bot.no-photo-url:https://placehold.co/800x600/e2e8f0/94a3b8.png}")
  private String noPhotoUrl;

  private final UserService userService;
  private final FavoriteService favoriteService;
  private final MainMenuKeyboardFactory keyboardFactory;
  private final ListingFormatter listingFormatter;
  private final PhotoProxyClient photoProxyClient;
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
    renderPage(callbackQuery.getFrom().getId(), chatIdOf(callbackQuery),
        TelegramPrivateChatGuard.isPrivateChat(callbackQuery), 0);
  }

  /**
   * Renders the first page of the user's favorites list from the {@code /favorites} text command
   * (issue #473) — same rendering as {@link #handle(CallbackQuery)}, no new business logic.
   *
   * @param telegramId    Telegram user identifier, never null
   * @param chatId        target chat identifier, never null
   * @param isPrivateChat whether the command was sent in a private one-on-one chat
   */
  public void handleCommand(Long telegramId, String chatId, boolean isPrivateChat) {
    renderPage(telegramId, chatId, isPrivateChat, 0);
  }

  /**
   * Handles a {@code FAV:PAGE:NEXT}/{@code FAV:PAGE:PREV} pagination callback.
   *
   * @param callbackQuery the incoming callback query, never null
   */
  public void handlePage(CallbackQuery callbackQuery) {
    Long telegramId = callbackQuery.getFrom().getId();
    String chatId = chatIdOf(callbackQuery);
    var session = pageSessions.get(telegramId);
    if (session == null) {
      sendText(chatId, SESSION_EXPIRED_TEXT);
      return;
    }
    int next = PAGE_NEXT.equals(callbackQuery.getData())
        ? Math.min(session.page() + 1, session.totalPages() - 1)
        : Math.max(session.page() - 1, 0);
    renderPage(telegramId, chatId, TelegramPrivateChatGuard.isPrivateChat(callbackQuery), next);
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

  private void renderPage(Long telegramId, String chatId, boolean isPrivateChat, int page) {
    if (!isPrivateChat) {
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
    sendPage(chatId, result.getContent(), page, result.getTotalPages());
  }

  /**
   * Sends one photo card per favorite followed by a shared pagination/menu message (issue #494),
   * mirroring {@link SearchResultSender}'s card-per-listing rendering. A single broken item (e.g.
   * a data-integrity gap with no linked listing) falls back to an error line instead of taking
   * down the whole page — see {@link #sendItemCard}.
   *
   * @param chatId     target chat identifier, never null
   * @param items      favorites on this page, never null, never empty
   * @param page       zero-based page index
   * @param totalPages total number of pages
   */
  private void sendPage(String chatId, List<FavoriteResponse> items, int page, int totalPages) {
    for (var item : items) {
      try {
        sendItemCard(chatId, item);
      } catch (Exception e) {
        log.error("Unexpected error sending favorite card: favoriteId={}", item.id(), e);
      }
    }
    sendNavigationMessage(chatId, page, totalPages);
  }

  private void sendItemCard(String chatId, FavoriteResponse item) {
    String caption;
    InlineKeyboardMarkup keyboard;
    try {
      caption = buildCaption(item);
      keyboard = buildItemKeyboard(item);
    } catch (Exception e) {
      log.error("Unexpected error formatting favorite item: favoriteId={}", item.id(), e);
      sendTextCard(chatId, ITEM_FORMAT_ERROR_TEXT, null);
      return;
    }
    sendPhotoOrPlaceholder(chatId, item, caption, keyboard);
  }

  private String buildCaption(FavoriteResponse item) {
    var caption = new StringBuilder(listingFormatter.buildCaption(item.listing()));
    if (item.listingInactive()) {
      caption.append("\n").append(LABEL_LISTING_INACTIVE);
    } else if (item.priceChanged()) {
      caption.append("\n💱 Цена изменилась: ").append(formatDelta(item));
    }
    return caption.toString();
  }

  private InlineKeyboardMarkup buildItemKeyboard(FavoriteResponse item) {
    var builder = InlineKeyboardMarkup.builder();
    listingFormatter.buildKeyboard(item.listing().sourceUrl()).getKeyboard().forEach(builder::keyboardRow);
    removeButtonSafely(item).ifPresent(button -> builder.keyboardRow(new InlineKeyboardRow(button)));
    return builder.build();
  }

  /**
   * Sends a favorite's photo, downloading the real photo via {@link PhotoProxyClient} when a
   * usable URL is present and falling back to the configured placeholder on a missing URL, a
   * failed download, or a rejected upload — the same fallback chain {@link SearchResultSender}
   * uses for search cards, minus its oversized-photo compression/re-encode handling (issue #494:
   * left out deliberately, favorites is a small curated list rather than a paginated feed of
   * fresh unvetted source photos; revisit if this turns out to matter in practice).
   *
   * @param chatId   target chat identifier, never null
   * @param item     the favorite whose listing photo is being sent, never null
   * @param caption  pre-built HTML caption, never null
   * @param keyboard pre-built inline keyboard, never null
   */
  private void sendPhotoOrPlaceholder(String chatId, FavoriteResponse item, String caption, InlineKeyboardMarkup keyboard) {
    String photoUrl = item.listing().photoUrl();
    if (!hasUsablePhotoUrl(photoUrl)) {
      sendPlaceholderPhoto(chatId, caption, keyboard);
      return;
    }
    if (photoProxyClient.isKufarCdnUrl(photoUrl)) {
      sendDirectUrlPhoto(chatId, photoUrl, caption, keyboard, item.listing().id());
      return;
    }
    var photoBytes = photoProxyClient.download(photoUrl, item.listing().id());
    if (photoBytes.isEmpty()) {
      sendPlaceholderPhoto(chatId, caption, keyboard);
      return;
    }
    try {
      telegramClient.execute(SendPhoto.builder()
          .chatId(chatId)
          .photo(new InputFile(new ByteArrayInputStream(photoBytes.get()), extractPhotoFilename(photoUrl)))
          .caption(caption)
          .parseMode("HTML")
          .replyMarkup(keyboard)
          .build());
    } catch (TelegramApiException e) {
      log.warn("Failed to send favorite photo, falling back to placeholder: favoriteId={}, listingId={}",
          item.id(), item.listing().id(), e);
      sendPlaceholderPhoto(chatId, caption, keyboard);
    }
  }

  /**
   * Sends a Kufar photo to Telegram as a direct URL, bypassing {@link PhotoProxyClient} entirely
   * (issue #515) — see {@link SearchResultSender}'s equivalent method for the full rationale
   * (issues #497, #511). Falls back to the placeholder, not to {@link PhotoProxyClient}, if
   * Telegram itself rejects the direct URL.
   *
   * @param chatId    target chat identifier, never null
   * @param photoUrl  the Kufar CDN photo URL, never null
   * @param caption   pre-built HTML caption, never null
   * @param keyboard  pre-built inline keyboard, never null
   * @param listingId used only for logging
   */
  private void sendDirectUrlPhoto(String chatId, String photoUrl, String caption,
      InlineKeyboardMarkup keyboard, Long listingId) {
    try {
      telegramClient.execute(SendPhoto.builder()
          .chatId(chatId)
          .photo(new InputFile(photoUrl))
          .caption(caption)
          .parseMode("HTML")
          .replyMarkup(keyboard)
          .build());
    } catch (TelegramApiException e) {
      log.warn("Direct-URL Kufar photo send failed, falling back to placeholder: listingId={}, url={}",
          listingId, photoUrl, e);
      sendPlaceholderPhoto(chatId, caption, keyboard);
    }
  }

  private void sendPlaceholderPhoto(String chatId, String caption, InlineKeyboardMarkup keyboard) {
    try {
      telegramClient.execute(SendPhoto.builder()
          .chatId(chatId)
          .photo(new InputFile(noPhotoUrl))
          .caption(caption)
          .parseMode("HTML")
          .replyMarkup(keyboard)
          .build());
    } catch (TelegramApiException e) {
      log.warn("Failed to send favorites placeholder photo, falling back to text: chatId={}", chatId, e);
      sendTextCard(chatId, caption, keyboard);
    }
  }

  private void sendTextCard(String chatId, String text, InlineKeyboardMarkup keyboard) {
    try {
      var builder = SendMessage.builder().chatId(chatId).text(text).parseMode("HTML");
      if (keyboard != null) {
        builder.replyMarkup(keyboard);
      }
      telegramClient.execute(builder.build());
    } catch (TelegramApiException e) {
      log.error("Failed to send favorites text card: chatId={}", chatId, e);
    }
  }

  private void sendNavigationMessage(String chatId, int page, int totalPages) {
    String pageText = "📄 Страница " + (page + 1) + " из " + totalPages;
    var navButtons = navButtons(page, totalPages);
    var builder = InlineKeyboardMarkup.builder();
    if (!navButtons.isEmpty()) {
      builder.keyboardRow(new InlineKeyboardRow(navButtons));
    }
    builder.keyboardRow(new InlineKeyboardRow(navBtn("🏠 Главное меню", SearchResultSender.ACTION_MENU)));
    try {
      telegramClient.execute(SendMessage.builder()
          .chatId(chatId)
          .text(pageText)
          .replyMarkup(builder.build())
          .build());
    } catch (TelegramApiException e) {
      log.warn("Failed to send favorites navigation message: chatId={}", chatId, e);
    }
  }

  private boolean hasUsablePhotoUrl(String url) {
    return url != null && (url.startsWith("http://") || url.startsWith("https://"));
  }

  private String extractPhotoFilename(String url) {
    int slash = url.lastIndexOf('/');
    int query = url.indexOf('?');
    String name = slash >= 0
        ? (query > slash ? url.substring(slash + 1, query) : url.substring(slash + 1))
        : "photo.jpg";
    return name.isBlank() ? "photo.jpg" : name;
  }

  private Optional<InlineKeyboardButton> removeButtonSafely(FavoriteResponse item) {
    try {
      return Optional.of(InlineKeyboardButton.builder()
          .text("❌ Убрать из избранного")
          .callbackData(REMOVE_PREFIX + item.listing().id())
          .build());
    } catch (Exception e) {
      log.error("Unexpected error building remove button: favoriteId={}", item.id(), e);
      return Optional.empty();
    }
  }

  private List<InlineKeyboardButton> navButtons(int page, int totalPages) {
    var navButtons = new ArrayList<InlineKeyboardButton>();
    if (page > 0) {
      navButtons.add(navBtn("← Предыдущие", PAGE_PREV));
    }
    if (page < totalPages - 1) {
      navButtons.add(navBtn("Ещё →", PAGE_NEXT));
    }
    return navButtons;
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

  private String chatIdOf(CallbackQuery callbackQuery) {
    return String.valueOf(callbackQuery.getMessage().getChatId());
  }
}
