package com.flatio.telegram.handler;

import com.flatio.domain.listing.ListingStatus;
import com.flatio.service.ListingService;
import com.flatio.telegram.formatter.ListingFormatter;
import com.flatio.telegram.state.SearchFilterState;
import com.flatio.telegram.state.SearchFilterWizard;
import com.flatio.web.dto.ListingSearchCriteria;
import com.flatio.web.dto.ListingSummaryResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

/**
 * Handles the search execution triggered by the {@code FILTER:SEARCH} callback.
 *
 * <p>Converts the current wizard state into {@link ListingSearchCriteria}, queries
 * {@link ListingService}, and sends formatted listing cards to the user.
 * Photo cards are sent via {@code sendPhoto} when a photo URL is available;
 * text cards via {@code sendMessage} otherwise.
 *
 * <p>Note: pagination navigation is not yet implemented — only the first page of results
 * is shown. Navigation will be added in issue #30.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class SearchResultSender {

  private static final int PAGE_SIZE = 5;
  private static final String SEARCHING_TEXT = "🔍 Ищу объявления...";
  private static final String NO_RESULTS_TEXT =
      "По вашим фильтрам объявлений не найдено.\nПопробуйте изменить параметры поиска.";
  private static final String NO_FILTERS_TEXT =
      "Пожалуйста, сначала настройте фильтры поиска.";

  private final SearchFilterWizard wizard;
  private final ListingService listingService;
  private final ListingFormatter listingFormatter;
  private final TelegramClient telegramClient;

  /**
   * Executes the search flow for a {@code FILTER:SEARCH} callback.
   *
   * <p>Reads the current wizard state, replaces the wizard message with a "searching" indicator,
   * fetches the first page of results, and sends a formatted card per listing.
   * If no state exists the user receives a prompt to configure filters first.
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

    log.debug("Sending {} result cards: telegramId={}", page.getNumberOfElements(), telegramId);
    page.getContent().forEach(listing -> sendCard(chatId, listing));
  }

  private void sendCard(String chatId, ListingSummaryResponse listing) {
    try {
      String caption = listingFormatter.buildCaption(listing);
      var keyboard = listingFormatter.buildKeyboard(listing.sourceUrl());
      if (listing.photoUrl() != null) {
        telegramClient.execute(SendPhoto.builder()
            .chatId(chatId)
            .photo(new InputFile(listing.photoUrl()))
            .caption(caption)
            .parseMode("HTML")
            .replyMarkup(keyboard)
            .build());
      } else {
        telegramClient.execute(SendMessage.builder()
            .chatId(chatId)
            .text(caption)
            .parseMode("HTML")
            .replyMarkup(keyboard)
            .build());
      }
    } catch (TelegramApiException e) {
      log.error("Failed to send listing card: listingId={}, chatId={}", listing.id(), chatId, e);
    }
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
        null,
        state.getOwnerOnly()
    );
  }
}
