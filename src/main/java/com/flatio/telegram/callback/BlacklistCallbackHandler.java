package com.flatio.telegram.callback;

import com.flatio.common.exception.BlacklistEntryNotFoundException;
import com.flatio.common.exception.BlacklistInvalidValueException;
import com.flatio.common.exception.BlacklistKeywordLimitExceededException;
import com.flatio.common.exception.ListingNotFoundException;
import com.flatio.common.exception.SourceNotFoundException;
import com.flatio.common.util.TelegramHtmlEscaper;
import com.flatio.common.util.TelegramPrivateChatGuard;
import com.flatio.domain.blacklist.BlacklistEntryType;
import com.flatio.service.BlacklistService;
import com.flatio.service.ListingService;
import com.flatio.service.UserService;
import com.flatio.telegram.config.SourceDisplayProperties;
import com.flatio.telegram.handler.SearchResultSender;
import com.flatio.telegram.keyboard.MainMenuKeyboardFactory;
import com.flatio.telegram.state.BlacklistKeywordPromptState;
import com.flatio.web.dto.BlacklistEntryResponse;
import com.flatio.web.dto.CreateBlacklistEntryRequest;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
 * Handles the "🚫 Чёрный список" section: hiding a listing/source from a search card, viewing the
 * blacklist filtered by type, adding a stop-word via free text, and removing entries (issue #459).
 *
 * <p>Reuses {@link BlacklistService}, the same service backing the REST {@code /api/v1/blacklist}
 * endpoint (Telegram and REST API share services, per project architecture rules). A page of
 * entries — filtered by type and paginated the same way as {@link FavoritesCallbackHandler} —
 * renders as a single Telegram message: one line per entry, one "delete" button row per entry,
 * plus the type-filter row, pagination row, and navigation (issue #477).
 *
 * <p>Restricted to private chats (issue #463): the blacklist is personal data, so a request made
 * from a group/supergroup/channel is answered with a redirect to a private chat instead of the
 * actual list — see {@link TelegramPrivateChatGuard}.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class BlacklistCallbackHandler {

  /** Callback data value that opens the blacklist section. */
  public static final String ACTION_BLACKLIST = MainMenuKeyboardFactory.ACTION_BLACKLIST;
  /** Callback prefix for every callback this handler owns. */
  public static final String CALLBACK_PREFIX = "BL:";
  /** Callback data for starting the "add stop-word" free-text prompt. */
  public static final String ADD_KEYWORD = "BL:ADD_KEYWORD";
  /** Callback data for cancelling the pending "add stop-word" prompt (issue #508). */
  public static final String CANCEL_KEYWORD = "BL:CANCEL_KEYWORD";
  /** Callback prefix for the type-filter buttons (suffix: {@code ALL}, or a {@link BlacklistEntryType} name). */
  public static final String FILTER_PREFIX = "BL:FILTER:";
  /** Callback prefix for deleting a blacklist entry. */
  public static final String DELETE_PREFIX = "BL:DELETE:";
  /** Callback prefix for hiding a listing from a search result card. */
  public static final String HIDE_LISTING_PREFIX = "BL:HIDE_LISTING:";
  /** Callback prefix for hiding a source from a search result card. */
  public static final String HIDE_SOURCE_PREFIX = "BL:HIDE_SOURCE:";
  /** Callback prefix for blacklist-list pagination. */
  public static final String PAGE_PREFIX = "BL:PAGE:";
  /** Callback data for advancing to the next blacklist page. */
  public static final String PAGE_NEXT = "BL:PAGE:NEXT";
  /** Callback data for going back to the previous blacklist page. */
  public static final String PAGE_PREV = "BL:PAGE:PREV";

  private static final int PAGE_SIZE = 5;
  private static final long SESSION_TTL_MINUTES = 30;
  private static final long MAX_SESSIONS = 10_000;
  private static final int MAX_KEYWORD_LENGTH = 100;

  private static final String EMPTY_TEXT = "🚫 Ваш чёрный список пока пуст."
      + "\n\nСкрывайте объявления и источники прямо с их карточки в поиске, либо добавьте стоп-слово кнопкой ниже.";
  private static final String HINT_TEXT =
      "Объявления и источники скрываются кнопкой «🚫 Скрыть» с карточки в поиске."
      + " Стоп-слова добавляются кнопкой «➕ Добавить стоп-слово» ниже."
      + " Номер на кнопке «Удалить» соответствует порядку записи в списке выше.";
  private static final String SESSION_EXPIRED_TEXT = "Список устарел. Откройте раздел «🚫 Чёрный список» заново.";
  private static final String FORMAT_ERROR_LINE = "⚠️ Не удалось отобразить запись";
  private static final String KEYWORD_PROMPT_TEXT = "Введите стоп-слово:";
  private static final String INVALID_KEYWORD_TEXT =
      "Стоп-слово не может быть пустым и должно быть не длиннее 100 символов. Введите его ещё раз:";
  private static final String UNREGISTERED_TEXT = "Сначала запустите бота командой /start.";
  private static final String UNREGISTERED_TOAST = UNREGISTERED_TEXT;
  private static final String LIMIT_EXCEEDED_TEXT =
      "Достигнут лимит стоп-слов по вашему тарифу. Удалите одно из существующих, чтобы добавить новое.";
  private static final String DELETED_TOAST = "🗑 Запись удалена из чёрного списка";
  private static final String NOT_FOUND_TOAST = "Запись не найдена.";
  private static final String HIDDEN_LISTING_TOAST = "🚫 Объявление скрыто";
  private static final String HIDDEN_SOURCE_TOAST = "🚫 Источник скрыт";

  private final UserService userService;
  private final BlacklistService blacklistService;
  private final ListingService listingService;
  private final SourceDisplayProperties sourceDisplayProperties;
  private final MainMenuKeyboardFactory keyboardFactory;
  private final BlacklistKeywordPromptState keywordPromptState;
  private final TelegramClient telegramClient;

  private record PageState(int page, int totalPages, BlacklistEntryType type) {}

  // Caffeine, not a plain map, so an abandoned pagination session is evicted instead of occupying
  // memory for the lifetime of the JVM — same rationale as FavoritesCallbackHandler#pageSessions.
  private final Map<Long, PageState> pageSessions = Caffeine.newBuilder()
      .expireAfterAccess(Duration.ofMinutes(SESSION_TTL_MINUTES))
      .maximumSize(MAX_SESSIONS)
      .<Long, PageState>build()
      .asMap();

  /**
   * Renders the first page of the blacklist, resetting the type filter to "all".
   *
   * @param callbackQuery the incoming callback query, never null
   */
  public void handle(CallbackQuery callbackQuery) {
    renderList(callbackQuery.getFrom().getId(), String.valueOf(callbackQuery.getMessage().getChatId()),
        TelegramPrivateChatGuard.isPrivateChat(callbackQuery), null, 0);
  }

  /**
   * Renders the first page of the blacklist, resetting the type filter to "all", from the
   * {@code /blacklist} text command (issue #473) — same rendering as {@link #handle(CallbackQuery)},
   * no new business logic.
   *
   * @param telegramId    Telegram user identifier, never null
   * @param chatId        target chat identifier, never null
   * @param isPrivateChat whether the command was sent in a private one-on-one chat
   */
  public void handleCommand(Long telegramId, String chatId, boolean isPrivateChat) {
    renderList(telegramId, chatId, isPrivateChat, null, 0);
  }

  /**
   * Handles a {@code BL:FILTER:<type>} callback, re-rendering the first page for the chosen type.
   *
   * @param callbackQuery the incoming callback query, never null
   */
  public void handleFilter(CallbackQuery callbackQuery) {
    renderList(callbackQuery.getFrom().getId(), String.valueOf(callbackQuery.getMessage().getChatId()),
        TelegramPrivateChatGuard.isPrivateChat(callbackQuery), parseFilterType(callbackQuery.getData()), 0);
  }

  /**
   * Handles a {@code BL:PAGE:NEXT}/{@code BL:PAGE:PREV} pagination callback.
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
    renderList(telegramId, chatId, TelegramPrivateChatGuard.isPrivateChat(callbackQuery), session.type(), next);
  }

  /**
   * Handles the {@code BL:ADD_KEYWORD} callback, prompting the user for a stop-word via free text.
   *
   * @param callbackQuery the incoming callback query, never null
   */
  public void handleAddKeywordPrompt(CallbackQuery callbackQuery) {
    Long telegramId = callbackQuery.getFrom().getId();
    String chatId = String.valueOf(callbackQuery.getMessage().getChatId());
    if (!TelegramPrivateChatGuard.isPrivateChat(callbackQuery)) {
      sendPlainText(chatId, TelegramPrivateChatGuard.PRIVATE_CHAT_REQUIRED_TEXT);
      return;
    }
    keywordPromptState.await(telegramId);
    sendKeywordPrompt(chatId, KEYWORD_PROMPT_TEXT);
  }

  /**
   * Handles the {@code BL:CANCEL_KEYWORD} callback, cancelling the pending stop-word prompt and
   * returning the user to the blacklist list (issue #508) — the prompt otherwise had no way to
   * back out of without typing something.
   *
   * @param callbackQuery the incoming callback query, never null
   */
  public void handleCancelKeyword(CallbackQuery callbackQuery) {
    keywordPromptState.clear(callbackQuery.getFrom().getId());
    handle(callbackQuery);
  }

  /**
   * Checks whether the given user is currently expected to reply with a stop-word.
   *
   * @param telegramId Telegram user identifier, never null
   * @return true if a stop-word prompt is pending for this user
   */
  public boolean isAwaitingKeyword(Long telegramId) {
    return keywordPromptState.isAwaiting(telegramId);
  }

  /**
   * Applies free-text stop-word input, validates it, and adds it to the blacklist.
   *
   * <p>Called when the user types a message while {@link #isAwaitingKeyword} is true. An invalid
   * stop-word re-prompts instead of clearing the pending state (issue #459 FR-BL-3: not blank,
   * at most 100 characters).
   *
   * @param telegramId Telegram user identifier, never null
   * @param chatId     target chat identifier, never null
   * @param text       user-provided stop-word text
   */
  public void handleKeywordText(Long telegramId, String chatId, String text) {
    String keyword = text == null ? "" : text.strip();
    if (keyword.isBlank() || keyword.length() > MAX_KEYWORD_LENGTH) {
      sendKeywordPrompt(chatId, INVALID_KEYWORD_TEXT);
      return;
    }
    var userOpt = userService.findByTelegramId(telegramId);
    if (userOpt.isEmpty()) {
      keywordPromptState.clear(telegramId);
      sendPlainText(chatId, UNREGISTERED_TEXT);
      return;
    }
    addKeyword(telegramId, chatId, userOpt.get().getId(), keyword);
  }

  private void addKeyword(Long telegramId, String chatId, Long userId, String keyword) {
    try {
      blacklistService.create(userId, new CreateBlacklistEntryRequest(BlacklistEntryType.KEYWORD, keyword));
      keywordPromptState.clear(telegramId);
      sendPlainText(chatId, "🚫 Стоп-слово «" + TelegramHtmlEscaper.escapeHtml(keyword) + "» добавлено.");
    } catch (BlacklistKeywordLimitExceededException e) {
      keywordPromptState.clear(telegramId);
      sendPlainText(chatId, LIMIT_EXCEEDED_TEXT);
    } catch (BlacklistInvalidValueException e) {
      sendKeywordPrompt(chatId, INVALID_KEYWORD_TEXT);
    }
  }

  /**
   * Handles a {@code BL:DELETE:<id>} callback.
   *
   * @param callbackQuery the incoming callback query, never null
   * @return toast text to show the user via {@code AnswerCallbackQuery}, never null
   */
  public String handleDelete(CallbackQuery callbackQuery) {
    if (!TelegramPrivateChatGuard.isPrivateChat(callbackQuery)) {
      return TelegramPrivateChatGuard.PRIVATE_CHAT_REQUIRED_TEXT;
    }
    var userOpt = userService.findByTelegramId(callbackQuery.getFrom().getId());
    if (userOpt.isEmpty()) {
      return UNREGISTERED_TOAST;
    }
    Long id = parseId(callbackQuery.getData(), DELETE_PREFIX);
    if (id == null) {
      return NOT_FOUND_TOAST;
    }
    try {
      blacklistService.delete(userOpt.get().getId(), id);
    } catch (BlacklistEntryNotFoundException e) {
      log.debug("BL:DELETE for already-deleted entry: id={}", id);
    }
    return DELETED_TOAST;
  }

  /**
   * Handles a {@code BL:HIDE_LISTING:<listingId>} callback from a search result card.
   *
   * @param callbackQuery the incoming callback query, never null
   * @return toast text to show the user via {@code AnswerCallbackQuery}, never null
   */
  public String handleHideListing(CallbackQuery callbackQuery) {
    if (!TelegramPrivateChatGuard.isPrivateChat(callbackQuery)) {
      return TelegramPrivateChatGuard.PRIVATE_CHAT_REQUIRED_TEXT;
    }
    var userOpt = userService.findByTelegramId(callbackQuery.getFrom().getId());
    if (userOpt.isEmpty()) {
      return UNREGISTERED_TOAST;
    }
    Long listingId = parseId(callbackQuery.getData(), HIDE_LISTING_PREFIX);
    if (listingId == null) {
      return NOT_FOUND_TOAST;
    }
    try {
      blacklistService.create(userOpt.get().getId(),
          new CreateBlacklistEntryRequest(BlacklistEntryType.LISTING, String.valueOf(listingId)));
      return HIDDEN_LISTING_TOAST;
    } catch (ListingNotFoundException | BlacklistInvalidValueException e) {
      return NOT_FOUND_TOAST;
    }
  }

  /**
   * Handles a {@code BL:HIDE_SOURCE:<sourceCode>} callback from a search result card.
   *
   * @param callbackQuery the incoming callback query, never null
   * @return toast text to show the user via {@code AnswerCallbackQuery}, never null
   */
  public String handleHideSource(CallbackQuery callbackQuery) {
    if (!TelegramPrivateChatGuard.isPrivateChat(callbackQuery)) {
      return TelegramPrivateChatGuard.PRIVATE_CHAT_REQUIRED_TEXT;
    }
    var userOpt = userService.findByTelegramId(callbackQuery.getFrom().getId());
    if (userOpt.isEmpty()) {
      return UNREGISTERED_TOAST;
    }
    String sourceCode = callbackQuery.getData().substring(HIDE_SOURCE_PREFIX.length());
    try {
      blacklistService.create(userOpt.get().getId(), new CreateBlacklistEntryRequest(BlacklistEntryType.SOURCE, sourceCode));
      return HIDDEN_SOURCE_TOAST;
    } catch (SourceNotFoundException | BlacklistInvalidValueException e) {
      return NOT_FOUND_TOAST;
    }
  }

  /**
   * Renders one page of the blacklist, or the empty-state message with a search shortcut when the
   * user has no entries for the active filter (issue #474).
   *
   * @param telegramId    Telegram user identifier, never null
   * @param chatId        target chat identifier, never null
   * @param isPrivateChat whether the request originated from a private one-on-one chat
   * @param type          active type filter, or null for "all"
   * @param page           zero-based page index
   */
  private void renderList(Long telegramId, String chatId, boolean isPrivateChat, BlacklistEntryType type, int page) {
    if (!isPrivateChat) {
      log.debug("BL callback rejected outside a private chat: chatId={}", chatId);
      sendText(chatId, TelegramPrivateChatGuard.PRIVATE_CHAT_REQUIRED_TEXT);
      return;
    }

    var userOpt = userService.findByTelegramId(telegramId);
    if (userOpt.isEmpty()) {
      log.warn("BL callback from unregistered telegramId={}", telegramId);
      pageSessions.remove(telegramId);
      sendText(chatId, EMPTY_TEXT);
      return;
    }

    var pageable = PageRequest.of(page, PAGE_SIZE, Sort.by(Sort.Direction.DESC, "createdAt"));
    var result = blacklistService.findByUser(userOpt.get().getId(), type, pageable);
    log.debug("Blacklist rendered: telegramId={}, type={}, page={}, totalPages={}",
        telegramId, type, page, result.getTotalPages());

    if (result.isEmpty()) {
      pageSessions.remove(telegramId);
      sendEmptyState(chatId, type);
    } else {
      pageSessions.put(telegramId, new PageState(page, result.getTotalPages(), type));
      sendListMessage(chatId, result.getContent(), type, page, result.getTotalPages());
    }
  }

  private BlacklistEntryType parseFilterType(String data) {
    String value = data.substring(FILTER_PREFIX.length());
    if ("ALL".equals(value)) {
      return null;
    }
    try {
      return BlacklistEntryType.valueOf(value);
    } catch (IllegalArgumentException e) {
      log.warn("Unknown blacklist filter value in callback: {}", data);
      return null;
    }
  }

  /**
   * Renders one page of the blacklist as a single message: one line per entry, one delete-button
   * row per entry, the type-filter row, an optional pagination row, and navigation (issue #477) —
   * including the hint about where LISTING/SOURCE entries come from (issue #474).
   *
   * <p>A formatting failure for a single entry falls back to {@link #FORMAT_ERROR_LINE} instead of
   * failing the whole page — see {@link #formatItemLineSafely}.
   *
   * @param chatId     target chat identifier, never null
   * @param items      entries on this page, never null, never empty
   * @param type       active type filter, or null for "all"
   * @param page       zero-based page index
   * @param totalPages total number of pages for this filter
   */
  private void sendListMessage(String chatId, List<BlacklistEntryResponse> items, BlacklistEntryType type,
      int page, int totalPages) {
    try {
      telegramClient.execute(SendMessage.builder()
          .chatId(chatId)
          .text(buildListText(items, type))
          .parseMode("HTML")
          .replyMarkup(buildListKeyboard(items, type, page, totalPages))
          .build());
    } catch (TelegramApiException e) {
      log.warn("Failed to send blacklist list: chatId={}", chatId, e);
    }
  }

  private String buildListText(List<BlacklistEntryResponse> items, BlacklistEntryType type) {
    Map<Long, String> listingLabels = resolveListingLabels(items);
    var textBuilder = new StringBuilder("🚫 Фильтр: ").append(type == null ? "Все" : typeLabel(type));
    for (int i = 0; i < items.size(); i++) {
      // Number matches the corresponding delete button in buildListKeyboard() (issue #513) —
      // both iterate the same items list in the same order, so index i is shared between them.
      textBuilder.append("\n").append(i + 1).append(". ").append(formatItemLineSafely(items.get(i), listingLabels));
    }
    textBuilder.append("\n\n").append(HINT_TEXT);
    return textBuilder.toString();
  }

  /**
   * Batch-resolves display labels for every LISTING-type entry on the page in a single query
   * (issue #525) — avoids one {@code listingService} lookup per row.
   *
   * @param items entries on this page, never null
   * @return map of listing ID to label, only for entries that resolved to one; never null
   */
  private Map<Long, String> resolveListingLabels(List<BlacklistEntryResponse> items) {
    var listingIds = items.stream()
        .filter(item -> item.type() == BlacklistEntryType.LISTING)
        .map(item -> parseListingIdOrNull(item.value()))
        .filter(Objects::nonNull)
        .toList();
    return listingIds.isEmpty() ? Map.of() : listingService.findDisplayLabelsByIds(listingIds);
  }

  /**
   * Builds the blacklist page's keyboard, including one numbered delete-button row per entry
   * (issue #498) — the number matches the entry's position in {@code items}, i.e. its line order
   * in {@link #buildListText}, so the user can tell which button deletes which line. Numbering is
   * per-page, not global across pagination — each page starts back at (1).
   */
  private InlineKeyboardMarkup buildListKeyboard(List<BlacklistEntryResponse> items, BlacklistEntryType type,
      int page, int totalPages) {
    var keyboardBuilder = InlineKeyboardMarkup.builder();
    for (int i = 0; i < items.size(); i++) {
      String label = "🗑 Удалить (" + (i + 1) + ")";
      keyboardBuilder.keyboardRow(new InlineKeyboardRow(navBtn(label, DELETE_PREFIX + items.get(i).id())));
    }
    keyboardBuilder.keyboardRow(filterRow(type));
    if (totalPages > 1) {
      keyboardBuilder.keyboardRow(paginationRow(page, totalPages));
    }
    keyboardBuilder.keyboardRow(new InlineKeyboardRow(navBtn("➕ Добавить стоп-слово", ADD_KEYWORD)));
    keyboardBuilder.keyboardRow(new InlineKeyboardRow(navBtn("🏠 Главное меню", SearchResultSender.ACTION_MENU)));
    return keyboardBuilder.build();
  }

  /**
   * Formats one blacklist entry as a text line, falling back to {@link #FORMAT_ERROR_LINE} instead
   * of propagating — a single malformed entry must not break the rest of the page.
   *
   * @param item the entry to format, never null
   * @return the formatted line, never null
   */
  private String formatItemLineSafely(BlacklistEntryResponse item, Map<Long, String> listingLabels) {
    try {
      return typeLabel(item.type()) + ": " + TelegramHtmlEscaper.escapeHtml(resolveDisplayValue(item, listingLabels));
    } catch (Exception e) {
      log.error("Unexpected error formatting blacklist item: entryId={}", item.id(), e);
      return FORMAT_ERROR_LINE;
    }
  }

  /**
   * Resolves the human-readable value shown per entry (issue #525) — a source code like
   * {@code KUFAR_APARTMENT_RENT} becomes its configured display name ("Kufar"), and a listing ID
   * becomes that listing's title/address; a stop-word is already human-readable as stored.
   *
   * @param item           the entry being formatted, never null
   * @param listingLabels  batch-resolved labels for this page's LISTING entries, never null
   * @return the value to display, never null
   */
  private String resolveDisplayValue(BlacklistEntryResponse item, Map<Long, String> listingLabels) {
    return switch (item.type()) {
      case SOURCE -> sourceDisplayProperties.findBySourceId(item.value())
          .map(SourceDisplayProperties.Entry::getDisplayName)
          .orElse(item.value());
      case LISTING -> resolveListingDisplayValue(item.value(), listingLabels);
      case KEYWORD -> item.value();
    };
  }

  private String resolveListingDisplayValue(String rawId, Map<Long, String> listingLabels) {
    Long id = parseListingIdOrNull(rawId);
    if (id == null) {
      return rawId;
    }
    return listingLabels.getOrDefault(id, "Объявление #" + id);
  }

  private Long parseListingIdOrNull(String value) {
    try {
      return Long.valueOf(value);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private InlineKeyboardRow filterRow(BlacklistEntryType current) {
    return new InlineKeyboardRow(
        filterBtn("Все", null, current),
        filterBtn(filterTypeLabel(BlacklistEntryType.LISTING), BlacklistEntryType.LISTING, current),
        filterBtn(filterTypeLabel(BlacklistEntryType.SOURCE), BlacklistEntryType.SOURCE, current),
        filterBtn(filterTypeLabel(BlacklistEntryType.KEYWORD), BlacklistEntryType.KEYWORD, current)
    );
  }

  /**
   * Plural type label used on filter buttons and in the empty-filter message (issue #506) — kept
   * separate from {@link #typeLabel}, which returns the singular form used per-entry in the list.
   *
   * @param type the type to label, never null
   * @return plural label for the type, never null
   */
  private String filterTypeLabel(BlacklistEntryType type) {
    return switch (type) {
      case LISTING -> "Объявления";
      case SOURCE -> "Источники";
      case KEYWORD -> "Стоп-слова";
    };
  }

  private InlineKeyboardRow paginationRow(int page, int totalPages) {
    var navButtons = new ArrayList<InlineKeyboardButton>();
    if (page > 0) {
      navButtons.add(navBtn("← Предыдущие", PAGE_PREV));
    }
    if (page < totalPages - 1) {
      navButtons.add(navBtn("Ещё →", PAGE_NEXT));
    }
    return new InlineKeyboardRow(navButtons);
  }

  private String typeLabel(BlacklistEntryType type) {
    if (type == null) {
      return "—";
    }
    return switch (type) {
      case LISTING -> "Объявление";
      case SOURCE -> "Источник";
      case KEYWORD -> "Стоп-слово";
    };
  }

  /**
   * Sends the "no entries" message with a shortcut into the search wizard, so the user is not
   * left at a dead end without knowing how to hide a first listing or source (issue #474).
   *
   * <p>Distinguishes "the blacklist is empty overall" ({@code type == null}) from "the selected
   * type filter has no entries, but other types might" ({@code type != null}, issue #506) — the
   * latter keeps the filter row visible so the user can switch type without leaving the section,
   * and uses a type-specific message instead of the generic {@link #EMPTY_TEXT}.
   *
   * @param chatId target chat identifier, never null
   * @param type   active type filter, or null when the whole blacklist is empty
   */
  private void sendEmptyState(String chatId, BlacklistEntryType type) {
    String text = type == null ? EMPTY_TEXT : "Записей типа «" + filterTypeLabel(type) + "» нет.";
    var keyboardBuilder = InlineKeyboardMarkup.builder();
    if (type != null) {
      keyboardBuilder.keyboardRow(filterRow(type));
    }
    var keyboard = keyboardBuilder
        .keyboardRow(new InlineKeyboardRow(navBtn("➕ Добавить стоп-слово", ADD_KEYWORD)))
        .keyboardRow(new InlineKeyboardRow(navBtn("🔍 Перейти к поиску", FilterCallbackHandler.ACTION_SEARCH)))
        .keyboardRow(new InlineKeyboardRow(navBtn("🏠 Главное меню", SearchResultSender.ACTION_MENU)))
        .build();
    try {
      telegramClient.execute(SendMessage.builder()
          .chatId(chatId)
          .text(text)
          .parseMode("HTML")
          .replyMarkup(keyboard)
          .build());
    } catch (TelegramApiException e) {
      log.warn("Failed to send blacklist empty state: chatId={}", chatId, e);
    }
  }

  private InlineKeyboardButton filterBtn(String label, BlacklistEntryType type, BlacklistEntryType current) {
    // issue #507: "• " was too subtle as an active-filter marker in a flat row of short buttons
    String text = type == current ? "✅ " + label : label;
    return navBtn(text, FILTER_PREFIX + (type == null ? "ALL" : type.name()));
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
      log.warn("Failed to send blacklist message: chatId={}", chatId, e);
    }
  }

  private void sendPlainText(String chatId, String text) {
    try {
      telegramClient.execute(SendMessage.builder().chatId(chatId).text(text).parseMode("HTML").build());
    } catch (TelegramApiException e) {
      log.warn("Failed to send blacklist prompt: chatId={}", chatId, e);
    }
  }

  /**
   * Sends the stop-word prompt (initial or re-prompt after invalid input) with a "Отмена" button,
   * so the user is never stuck waiting for text input with no way back (issue #508).
   *
   * @param chatId target chat identifier, never null
   * @param text   prompt text, never null
   */
  private void sendKeywordPrompt(String chatId, String text) {
    var keyboard = InlineKeyboardMarkup.builder()
        .keyboardRow(new InlineKeyboardRow(navBtn("Отмена", CANCEL_KEYWORD)))
        .build();
    try {
      telegramClient.execute(SendMessage.builder()
          .chatId(chatId)
          .text(text)
          .parseMode("HTML")
          .replyMarkup(keyboard)
          .build());
    } catch (TelegramApiException e) {
      log.warn("Failed to send blacklist keyword prompt: chatId={}", chatId, e);
    }
  }

  private InlineKeyboardButton navBtn(String text, String callbackData) {
    return InlineKeyboardButton.builder().text(text).callbackData(callbackData).build();
  }

  private Long parseId(String data, String prefix) {
    try {
      return Long.valueOf(data.substring(prefix.length()));
    } catch (RuntimeException e) {
      log.warn("Malformed blacklist callback data: {}", data);
      return null;
    }
  }
}
