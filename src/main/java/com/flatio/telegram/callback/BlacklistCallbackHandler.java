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
import com.flatio.service.UserService;
import com.flatio.telegram.handler.SearchResultSender;
import com.flatio.telegram.keyboard.MainMenuKeyboardFactory;
import com.flatio.telegram.state.BlacklistKeywordPromptState;
import com.flatio.web.dto.BlacklistEntryResponse;
import com.flatio.web.dto.CreateBlacklistEntryRequest;
import java.util.List;
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
 * endpoint (Telegram and REST API share services, per project architecture rules). Unlike the
 * read-only summary shipped in #456, this renders one Telegram message per entry (with its own
 * "delete" button) followed by a single navigation message carrying the type filter, matching the
 * shape already used for search results by {@link SearchResultSender}.
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
  /** Callback prefix for the type-filter buttons (suffix: {@code ALL}, or a {@link BlacklistEntryType} name). */
  public static final String FILTER_PREFIX = "BL:FILTER:";
  /** Callback prefix for deleting a blacklist entry. */
  public static final String DELETE_PREFIX = "BL:DELETE:";
  /** Callback prefix for hiding a listing from a search result card. */
  public static final String HIDE_LISTING_PREFIX = "BL:HIDE_LISTING:";
  /** Callback prefix for hiding a source from a search result card. */
  public static final String HIDE_SOURCE_PREFIX = "BL:HIDE_SOURCE:";

  private static final int MAX_LIST_SIZE = 20;
  private static final int MAX_KEYWORD_LENGTH = 100;

  private static final String EMPTY_TEXT = "🚫 Ваш чёрный список пока пуст.";
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
  private final MainMenuKeyboardFactory keyboardFactory;
  private final BlacklistKeywordPromptState keywordPromptState;
  private final TelegramClient telegramClient;

  /**
   * Renders the blacklist, resetting the type filter to "all".
   *
   * @param callbackQuery the incoming callback query, never null
   */
  public void handle(CallbackQuery callbackQuery) {
    renderList(callbackQuery.getFrom().getId(), String.valueOf(callbackQuery.getMessage().getChatId()),
        TelegramPrivateChatGuard.isPrivateChat(callbackQuery), null);
  }

  /**
   * Renders the blacklist, resetting the type filter to "all", from the {@code /blacklist} text
   * command (issue #473) — same rendering as {@link #handle(CallbackQuery)}, no new business logic.
   *
   * @param telegramId    Telegram user identifier, never null
   * @param chatId        target chat identifier, never null
   * @param isPrivateChat whether the command was sent in a private one-on-one chat
   */
  public void handleCommand(Long telegramId, String chatId, boolean isPrivateChat) {
    renderList(telegramId, chatId, isPrivateChat, null);
  }

  /**
   * Handles a {@code BL:FILTER:<type>} callback, re-rendering the list for the chosen type.
   *
   * @param callbackQuery the incoming callback query, never null
   */
  public void handleFilter(CallbackQuery callbackQuery) {
    renderList(callbackQuery.getFrom().getId(), String.valueOf(callbackQuery.getMessage().getChatId()),
        TelegramPrivateChatGuard.isPrivateChat(callbackQuery), parseFilterType(callbackQuery.getData()));
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
    sendPlainText(chatId, KEYWORD_PROMPT_TEXT);
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
      sendPlainText(chatId, INVALID_KEYWORD_TEXT);
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
      sendPlainText(chatId, INVALID_KEYWORD_TEXT);
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

  private void renderList(Long telegramId, String chatId, boolean isPrivateChat, BlacklistEntryType type) {
    if (!isPrivateChat) {
      log.debug("BL callback rejected outside a private chat: chatId={}", chatId);
      sendText(chatId, TelegramPrivateChatGuard.PRIVATE_CHAT_REQUIRED_TEXT);
      return;
    }

    var userOpt = userService.findByTelegramId(telegramId);
    if (userOpt.isEmpty()) {
      log.warn("BL callback from unregistered telegramId={}", telegramId);
      sendText(chatId, EMPTY_TEXT);
      return;
    }

    var pageable = PageRequest.of(0, MAX_LIST_SIZE, Sort.by(Sort.Direction.DESC, "createdAt"));
    var page = blacklistService.findByUser(userOpt.get().getId(), type, pageable);
    log.debug("Blacklist rendered: telegramId={}, type={}, count={}", telegramId, type, page.getNumberOfElements());
    if (page.isEmpty()) {
      sendText(chatId, EMPTY_TEXT);
    } else {
      sendItems(chatId, page.getContent());
    }
    sendNavigation(chatId, type);
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

  private void sendItems(String chatId, List<BlacklistEntryResponse> items) {
    for (var item : items) {
      try {
        sendItem(chatId, item);
      } catch (Exception e) {
        log.error("Unexpected error sending blacklist item: entryId={}", item.id(), e);
      }
    }
  }

  private void sendItem(String chatId, BlacklistEntryResponse item) {
    String text = typeLabel(item.type()) + ": " + TelegramHtmlEscaper.escapeHtml(item.value());
    var deleteButton = navBtn("🗑 Удалить", DELETE_PREFIX + item.id());
    var keyboard = InlineKeyboardMarkup.builder()
        .keyboardRow(new InlineKeyboardRow(deleteButton))
        .build();
    try {
      telegramClient.execute(SendMessage.builder()
          .chatId(chatId)
          .text(text)
          .parseMode("HTML")
          .replyMarkup(keyboard)
          .build());
    } catch (TelegramApiException e) {
      log.warn("Failed to send blacklist item: chatId={}", chatId, e);
    }
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

  private void sendNavigation(String chatId, BlacklistEntryType currentType) {
    var filterRow = new InlineKeyboardRow(
        filterBtn("Все", null, currentType),
        filterBtn("Объявления", BlacklistEntryType.LISTING, currentType),
        filterBtn("Источники", BlacklistEntryType.SOURCE, currentType),
        filterBtn("Стоп-слова", BlacklistEntryType.KEYWORD, currentType)
    );
    var keyboard = InlineKeyboardMarkup.builder()
        .keyboardRow(filterRow)
        .keyboardRow(new InlineKeyboardRow(navBtn("➕ Добавить стоп-слово", ADD_KEYWORD)))
        .keyboardRow(new InlineKeyboardRow(navBtn("🏠 Главное меню", SearchResultSender.ACTION_MENU)))
        .build();
    try {
      telegramClient.execute(SendMessage.builder()
          .chatId(chatId)
          .text("🚫 Фильтр: " + (currentType == null ? "Все" : typeLabel(currentType)))
          .replyMarkup(keyboard)
          .build());
    } catch (TelegramApiException e) {
      log.warn("Failed to send blacklist navigation: chatId={}", chatId, e);
    }
  }

  private InlineKeyboardButton filterBtn(String label, BlacklistEntryType type, BlacklistEntryType current) {
    String text = type == current ? "• " + label : label;
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
