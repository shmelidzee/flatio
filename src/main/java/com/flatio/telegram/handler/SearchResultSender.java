package com.flatio.telegram.handler;

import com.flatio.domain.listing.ListingStatus;
import com.flatio.service.ListingService;
import com.flatio.telegram.callback.FilterCallbackHandler;
import com.flatio.telegram.formatter.ListingFormatter;
import com.flatio.telegram.state.SearchFilterState;
import com.flatio.telegram.state.SearchSession;
import com.flatio.telegram.state.SearchFilterWizard;
import com.flatio.web.dto.ListingSearchCriteria;
import com.flatio.web.dto.ListingSummaryResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

/**
 * Handles search execution and paginated result delivery.
 *
 * <p>Triggered by the {@code FILTER:SEARCH} callback (initial search) and
 * {@code PAGE:NEXT} / {@code PAGE:PREV} callbacks (pagination).
 * Per-user {@link SearchSession} objects track the active criteria and current page.
 * Sessions expire after {@value #SESSION_TTL_MINUTES} minutes of inactivity.
 *
 * <p>Photo cards are sent via {@code sendPhoto}. When the listing has no photo URL a
 * configurable placeholder image ({@code telegram.bot.no-photo-url}) is used instead.
 * If the Telegram API rejects both the real URL and the placeholder, the card falls back
 * to a plain text message so the user always receives the listing details.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class SearchResultSender {

  /** Callback prefix for pagination navigation. */
  public static final String PAGE_CALLBACK_PREFIX = "PAGE:";
  /** Callback data for advancing to the next page. */
  public static final String PAGE_NEXT = "PAGE:NEXT";
  /** Callback data for going back to the previous page. */
  public static final String PAGE_PREV = "PAGE:PREV";

  private static final int PAGE_SIZE = 5;
  private static final long SESSION_TTL_MINUTES = 30;
  private static final Duration SESSION_TTL = Duration.ofMinutes(SESSION_TTL_MINUTES);

  private static final String SEARCHING_TEXT = "🔍 Ищу объявления...";
  private static final String NO_RESULTS_TEXT =
      "По вашим фильтрам объявлений не найдено.\nПопробуйте изменить параметры поиска.";
  private static final String NO_FILTERS_TEXT =
      "Пожалуйста, сначала настройте фильтры поиска.";
  private static final String SESSION_EXPIRED_TEXT =
      "Поиск устарел. Начните новый поиск.";

  @Value("${telegram.bot.no-photo-url:https://placehold.co/800x600/e2e8f0/94a3b8.png}")
  private String noPhotoUrl;

  private final SearchFilterWizard wizard;
  private final ListingService listingService;
  private final ListingFormatter listingFormatter;
  private final TelegramClient telegramClient;

  private final Map<Long, SearchSession> sessions = new ConcurrentHashMap<>();

  /**
   * Executes the search flow for a {@code FILTER:SEARCH} callback.
   *
   * <p>Reads the current wizard state, replaces the wizard message with a "searching" indicator,
   * fetches the first page of results, and sends a formatted card per listing followed by
   * a pagination navigation message.
   * If no wizard state exists the user receives a prompt to configure filters first.
   *
   * @param callbackQuery the incoming callback query, never null
   */
  public void handle(CallbackQuery callbackQuery) {
    Long telegramId = callbackQuery.getFrom().getId();
    String chatId = String.valueOf(callbackQuery.getMessage().getChatId());
    Integer messageId = callbackQuery.getMessage().getMessageId();

    var stateOpt = wizard.getState(telegramId);
    if (stateOpt.isEmpty()) {
      sendText(chatId, NO_FILTERS_TEXT);
      return;
    }

    editMessage(chatId, messageId, SEARCHING_TEXT);

    var criteria = buildCriteria(stateOpt.get());
    var pageable = PageRequest.of(0, PAGE_SIZE, Sort.by(Sort.Direction.DESC, "createdAt"));
    var page = listingService.search(criteria, pageable);

    if (page.isEmpty()) {
      log.debug("No results found: telegramId={}, criteria={}", telegramId, criteria);
      sendText(chatId, NO_RESULTS_TEXT);
      return;
    }

    sessions.put(telegramId, new SearchSession(criteria, 0, page.getTotalPages()));
    log.debug("Sending {} result cards: telegramId={}, totalPages={}", page.getNumberOfElements(), telegramId, page.getTotalPages());
    page.getContent().forEach(listing -> sendCard(chatId, listing));
    sendNavigationMessage(chatId, 0, page.getTotalPages());
  }

  /**
   * Handles a {@code PAGE:NEXT} or {@code PAGE:PREV} callback.
   *
   * <p>Looks up the active session for the user, computes the new page index,
   * fetches the corresponding results, and sends cards with an updated navigation message.
   * If the session has expired the user is prompted to start a new search.
   *
   * @param callbackQuery the incoming callback query, never null
   */
  public void handlePageCallback(CallbackQuery callbackQuery) {
    Long telegramId = callbackQuery.getFrom().getId();
    String chatId = String.valueOf(callbackQuery.getMessage().getChatId());
    String data = callbackQuery.getData();

    var session = getActiveSession(telegramId);
    if (session == null) {
      sendText(chatId, SESSION_EXPIRED_TEXT);
      return;
    }

    int newPage = PAGE_NEXT.equals(data)
        ? Math.min(session.getCurrentPage() + 1, session.getTotalPages() - 1)
        : Math.max(session.getCurrentPage() - 1, 0);

    var pageable = PageRequest.of(newPage, PAGE_SIZE, Sort.by(Sort.Direction.DESC, "createdAt"));
    var page = listingService.search(session.getCriteria(), pageable);

    if (page.isEmpty()) {
      sendText(chatId, NO_RESULTS_TEXT);
      return;
    }

    session.setCurrentPage(newPage);
    session.setTotalPages(page.getTotalPages());
    session.touch();

    log.debug("Sending page {} of {}: telegramId={}", newPage + 1, page.getTotalPages(), telegramId);
    page.getContent().forEach(listing -> sendCard(chatId, listing));
    sendNavigationMessage(chatId, newPage, page.getTotalPages());
  }

  private void sendCard(String chatId, ListingSummaryResponse listing) {
    String caption = listingFormatter.buildCaption(listing);
    var keyboard = listingFormatter.buildKeyboard(listing.sourceUrl());
    String photoUrl = resolvePhotoUrl(listing);

    try {
      telegramClient.execute(SendPhoto.builder()
          .chatId(chatId)
          .photo(new InputFile(photoUrl))
          .caption(caption)
          .parseMode("HTML")
          .replyMarkup(keyboard)
          .build());
    } catch (TelegramApiException e) {
      log.warn("Failed to send photo card, falling back to text: listingId={}, url={}", listing.id(), photoUrl, e);
      sendTextCard(chatId, caption, keyboard);
    }
  }

  private String resolvePhotoUrl(ListingSummaryResponse listing) {
    String url = listing.photoUrl();
    if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
      return url;
    }
    if (url != null && !url.isBlank()) {
      log.debug("Invalid photo url, using placeholder: listingId={}, url={}", listing.id(), url);
    }
    return noPhotoUrl;
  }

  private void sendTextCard(String chatId, String caption, InlineKeyboardMarkup keyboard) {
    try {
      telegramClient.execute(SendMessage.builder()
          .chatId(chatId)
          .text(caption)
          .parseMode("HTML")
          .replyMarkup(keyboard)
          .build());
    } catch (TelegramApiException e) {
      log.error("Failed to send text card: chatId={}", chatId, e);
    }
  }

  private void sendNavigationMessage(String chatId, int currentPage, int totalPages) {
    String pageText = "📄 Страница " + (currentPage + 1) + " из " + totalPages;
    var navButtons = new ArrayList<InlineKeyboardButton>();
    if (currentPage > 0) {
      navButtons.add(navBtn("← Предыдущие", PAGE_PREV));
    }
    if (currentPage < totalPages - 1) {
      navButtons.add(navBtn("Ещё →", PAGE_NEXT));
    }
    var newSearchBtn = navBtn("🔍 Новый поиск", FilterCallbackHandler.ACTION_SEARCH);

    var markupBuilder = InlineKeyboardMarkup.builder();
    if (!navButtons.isEmpty()) {
      markupBuilder.keyboardRow(new InlineKeyboardRow(navButtons));
    }
    markupBuilder.keyboardRow(new InlineKeyboardRow(newSearchBtn));

    try {
      telegramClient.execute(SendMessage.builder()
          .chatId(chatId)
          .text(pageText)
          .replyMarkup(markupBuilder.build())
          .build());
    } catch (TelegramApiException e) {
      log.error("Failed to send navigation message: chatId={}", chatId, e);
    }
  }

  private InlineKeyboardButton navBtn(String text, String callbackData) {
    return InlineKeyboardButton.builder().text(text).callbackData(callbackData).build();
  }

  private void editMessage(String chatId, Integer messageId, String text) {
    try {
      telegramClient.execute(EditMessageText.builder()
          .chatId(chatId)
          .messageId(messageId)
          .text(text)
          .build());
    } catch (TelegramApiException e) {
      log.warn("Failed to edit wizard message to searching state: chatId={}", chatId, e);
    }
  }

  private void sendText(String chatId, String text) {
    try {
      telegramClient.execute(SendMessage.builder()
          .chatId(chatId)
          .text(text)
          .build());
    } catch (TelegramApiException e) {
      log.error("Failed to send text message: chatId={}", chatId, e);
    }
  }

  private SearchSession getActiveSession(Long telegramId) {
    var session = sessions.get(telegramId);
    if (session == null) {
      return null;
    }
    if (session.getLastAccessedAt().plus(SESSION_TTL).isBefore(Instant.now())) {
      sessions.remove(telegramId);
      log.debug("Search session expired: telegramId={}", telegramId);
      return null;
    }
    return session;
  }

  private ListingSearchCriteria buildCriteria(SearchFilterState state) {
    return new ListingSearchCriteria(
        state.getDealType(),
        state.getPropertyType(),
        null,
        null,
        state.getPriceMin(),
        state.getPriceMax(),
        state.getRooms(),
        ListingStatus.ACTIVE,
        state.getQuery(),
        state.getOwnerOnly()
    );
  }
}
