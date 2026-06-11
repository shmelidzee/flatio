package com.flatio.telegram.handler;

import com.flatio.service.ListingService;
import com.flatio.telegram.formatter.ListingFormatter;
import com.flatio.telegram.state.SearchFilterState;
import com.flatio.telegram.state.SearchFilterWizard;
import com.flatio.web.dto.ListingSummaryResponse;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SearchResultSenderTest {

  @Mock
  private SearchFilterWizard wizard;

  @Mock
  private ListingService listingService;

  @Mock
  private ListingFormatter listingFormatter;

  @Mock
  private TelegramClient telegramClient;

  @InjectMocks
  private SearchResultSender searchResultSender;

  private SearchFilterState defaultState;

  @BeforeEach
  void setUp() {
    defaultState = new SearchFilterState();
  }

  // -------------------------------------------------------------------------
  // sendCard — photo vs text
  // -------------------------------------------------------------------------

  @Test
  void should_send_text_message_when_no_photo_url() throws TelegramApiException {
    // Given
    var listing = buildListing(1L, null, "https://realt.by/1");
    when(wizard.getState(1L)).thenReturn(Optional.of(defaultState));
    when(listingService.search(any(), any())).thenReturn(pageOf(listing));
    when(listingFormatter.buildCaption(listing)).thenReturn("caption");
    when(listingFormatter.buildKeyboard(anyString())).thenReturn(mock(InlineKeyboardMarkup.class));

    // When
    searchResultSender.handle(buildCallback(1L, 100L, 10));

    // Then
    verify(telegramClient).execute(any(SendMessage.class));
    verify(telegramClient, never()).execute(any(SendPhoto.class));
  }

  @Test
  void should_send_photo_message_when_photo_url_present() throws TelegramApiException {
    // Given
    var listing = buildListing(2L, "https://cdn.realt.by/photo.jpg", "https://realt.by/2");
    when(wizard.getState(1L)).thenReturn(Optional.of(defaultState));
    when(listingService.search(any(), any())).thenReturn(pageOf(listing));
    when(listingFormatter.buildCaption(listing)).thenReturn("caption");
    when(listingFormatter.buildKeyboard(anyString())).thenReturn(mock(InlineKeyboardMarkup.class));

    // When
    searchResultSender.handle(buildCallback(1L, 100L, 10));

    // Then
    verify(telegramClient).execute(any(SendPhoto.class));
    verify(telegramClient, never()).execute(any(SendMessage.class));
  }

  // -------------------------------------------------------------------------
  // no results
  // -------------------------------------------------------------------------

  @Test
  void should_send_no_results_message_when_search_returns_empty() throws TelegramApiException {
    // Given
    when(wizard.getState(1L)).thenReturn(Optional.of(defaultState));
    when(listingService.search(any(), any())).thenReturn(Page.empty());

    // When
    searchResultSender.handle(buildCallback(1L, 100L, 10));

    // Then
    verify(telegramClient).execute(any(SendMessage.class));
  }

  // -------------------------------------------------------------------------
  // no wizard state
  // -------------------------------------------------------------------------

  @Test
  void should_send_no_filters_message_when_wizard_state_absent() throws TelegramApiException {
    // Given
    when(wizard.getState(1L)).thenReturn(Optional.empty());

    // When
    searchResultSender.handle(buildCallback(1L, 100L, 10));

    // Then
    verify(telegramClient).execute(any(SendMessage.class));
    verify(listingService, never()).search(any(), any());
  }

  // -------------------------------------------------------------------------
  // edit message before sending results
  // -------------------------------------------------------------------------

  @Test
  void should_edit_wizard_message_to_searching_before_sending_results() throws TelegramApiException {
    // Given
    var listing = buildListing(3L, null, "https://realt.by/3");
    when(wizard.getState(1L)).thenReturn(Optional.of(defaultState));
    when(listingService.search(any(), any())).thenReturn(pageOf(listing));
    when(listingFormatter.buildCaption(listing)).thenReturn("caption");
    when(listingFormatter.buildKeyboard(anyString())).thenReturn(mock(InlineKeyboardMarkup.class));

    // When
    searchResultSender.handle(buildCallback(1L, 100L, 10));

    // Then — EditMessageText called once (searching indicator), SendMessage called once (card)
    verify(telegramClient).execute(any(EditMessageText.class));
    verify(telegramClient).execute(any(SendMessage.class));
  }

  // -------------------------------------------------------------------------
  // fault isolation per card
  // -------------------------------------------------------------------------

  @Test
  void should_not_throw_when_telegram_api_fails_for_one_card() throws TelegramApiException {
    // Given — three listings, telegram throws on the first send
    var listing1 = buildListing(4L, null, "https://realt.by/4");
    var listing2 = buildListing(5L, null, "https://realt.by/5");
    var listing3 = buildListing(6L, null, "https://realt.by/6");
    when(wizard.getState(1L)).thenReturn(Optional.of(defaultState));
    when(listingService.search(any(), any())).thenReturn(pageOf(listing1, listing2, listing3));
    when(listingFormatter.buildCaption(any())).thenReturn("caption");
    when(listingFormatter.buildKeyboard(anyString())).thenReturn(mock(InlineKeyboardMarkup.class));
    // lenient: editMessage also calls execute(), but we only need to stub SendMessage
    lenient().when(telegramClient.execute(any(EditMessageText.class))).thenReturn(mock());
    when(telegramClient.execute(any(SendMessage.class)))
        .thenThrow(new TelegramApiException("Telegram error"))
        .thenReturn(mock())
        .thenReturn(mock());

    // When / Then — no exception propagated
    assertThatNoException().isThrownBy(
        () -> searchResultSender.handle(buildCallback(1L, 100L, 10))
    );

    // And remaining cards are still attempted (3 SendMessage calls total)
    verify(telegramClient, times(3)).execute(any(SendMessage.class));
  }

  // -------------------------------------------------------------------------
  // helpers
  // -------------------------------------------------------------------------

  private static ListingSummaryResponse buildListing(Long id, String photoUrl, String sourceUrl) {
    return new ListingSummaryResponse(
        id,
        "Test listing " + id,
        BigDecimal.valueOf(500),
        "USD",
        null,
        2,
        null,
        BigDecimal.valueOf(52.0),
        "Минск",
        "Советский район",
        "realt",
        null,
        photoUrl,
        sourceUrl
    );
  }

  @SafeVarargs
  private static <T> Page<T> pageOf(T... items) {
    return new PageImpl<>(List.of(items), PageRequest.of(0, 5), items.length);
  }

  private static CallbackQuery buildCallback(long userId, long chatId, int messageId) {
    var user = mock(User.class);
    when(user.getId()).thenReturn(userId);

    var message = mock(Message.class);
    when(message.getChatId()).thenReturn(chatId);
    when(message.getMessageId()).thenReturn(messageId);

    var callback = mock(CallbackQuery.class);
    when(callback.getFrom()).thenReturn(user);
    when(callback.getMessage()).thenReturn(message);
    return callback;
  }
}
