package com.flatio.telegram.handler;

import com.flatio.service.ListingService;
import com.flatio.service.UserSavedSearchService;
import com.flatio.service.domain.SearchFilter;
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
import org.springframework.test.util.ReflectionTestUtils;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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

  @Mock
  private UserSavedSearchService userSavedSearchService;

  @InjectMocks
  private SearchResultSender searchResultSender;

  private SearchFilterState defaultState;

  private static final String TEST_NO_PHOTO_URL = "https://placeholder.test/no-photo.png";

  @BeforeEach
  void setUp() {
    defaultState = new SearchFilterState();
    // @Value is not injected by Mockito — set the placeholder URL explicitly
    ReflectionTestUtils.setField(searchResultSender, "noPhotoUrl", TEST_NO_PHOTO_URL);
  }

  // -------------------------------------------------------------------------
  // sendCard — photo vs text
  // -------------------------------------------------------------------------

  @Test
  void should_send_photo_with_placeholder_when_no_photo_url() throws TelegramApiException {
    // Given
    var listing = buildListing(1L, null, "https://realt.by/1");
    when(wizard.getState(1L)).thenReturn(Optional.of(defaultState));
    when(listingService.search(any(), any())).thenReturn(pageOf(listing));
    when(listingFormatter.buildCaption(listing)).thenReturn("caption");
    when(listingFormatter.buildKeyboard(anyString())).thenReturn(mock(InlineKeyboardMarkup.class));

    // When
    searchResultSender.handle(buildCallback(1L, 100L, 10));

    // Then — SendPhoto for the card (placeholder URL), SendMessage for navigation only
    verify(telegramClient).execute(any(SendPhoto.class));
    verify(telegramClient).execute(any(SendMessage.class));
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

    // Then — SendPhoto for the card + 1 SendMessage for navigation
    verify(telegramClient).execute(any(SendPhoto.class));
    verify(telegramClient).execute(any(SendMessage.class));
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

    // Then — EditMessageText once (searching indicator), SendPhoto for card (placeholder), SendMessage for navigation
    verify(telegramClient).execute(any(EditMessageText.class));
    verify(telegramClient).execute(any(SendPhoto.class));
    verify(telegramClient).execute(any(SendMessage.class));
  }

  // -------------------------------------------------------------------------
  // fault isolation per card
  // -------------------------------------------------------------------------

  @Test
  void should_not_throw_when_telegram_api_fails_for_one_card() throws TelegramApiException {
    // Given — three listings, telegram throws on first SendPhoto (card 1), succeeds for the rest
    var listing1 = buildListing(4L, null, "https://realt.by/4");
    var listing2 = buildListing(5L, null, "https://realt.by/5");
    var listing3 = buildListing(6L, null, "https://realt.by/6");
    when(wizard.getState(1L)).thenReturn(Optional.of(defaultState));
    when(listingService.search(any(), any())).thenReturn(pageOf(listing1, listing2, listing3));
    when(listingFormatter.buildCaption(any())).thenReturn("caption");
    when(listingFormatter.buildKeyboard(anyString())).thenReturn(mock(InlineKeyboardMarkup.class));
    lenient().when(telegramClient.execute(any(EditMessageText.class))).thenReturn(mock());
    when(telegramClient.execute(any(SendPhoto.class)))
        .thenThrow(new TelegramApiException("Telegram error"))
        .thenReturn(mock())
        .thenReturn(mock());

    // When / Then — no exception propagated
    assertThatNoException().isThrownBy(
        () -> searchResultSender.handle(buildCallback(1L, 100L, 10))
    );

    // 3 SendPhoto attempts (1 failed + 2 succeeded); 2 SendMessage: 1 text fallback + 1 navigation
    verify(telegramClient, times(3)).execute(any(SendPhoto.class));
    verify(telegramClient, times(2)).execute(any(SendMessage.class));
  }

  // -------------------------------------------------------------------------
  // photo URL validation — invalid schema fallback to placeholder
  // -------------------------------------------------------------------------

  @Test
  void should_use_placeholder_when_photo_url_has_no_http_schema() throws TelegramApiException {
    // Given — listing with a short invalid URL "g" (seen in production: url=g causes [400] Wrong string length)
    var listing = buildListing(10L, "g", "https://realt.by/10");
    when(wizard.getState(1L)).thenReturn(Optional.of(defaultState));
    when(listingService.search(any(), any())).thenReturn(pageOf(listing));
    when(listingFormatter.buildCaption(listing)).thenReturn("caption");
    when(listingFormatter.buildKeyboard(anyString())).thenReturn(mock(InlineKeyboardMarkup.class));
    lenient().when(telegramClient.execute(any(EditMessageText.class))).thenReturn(mock());
    // Simulate Telegram API rejecting the invalid URL "g"
    when(telegramClient.execute(any(SendPhoto.class))).thenAnswer(invocation -> {
      SendPhoto photo = invocation.getArgument(0);
      if ("g".equals(photo.getPhoto().getAttachName())) {
        throw new TelegramApiException("[400] Wrong string length");
      }
      return null;
    });

    // When
    searchResultSender.handle(buildCallback(1L, 100L, 10));

    // Then — no text card fallback: 1 SendMessage for navigation only (placeholder URL was used)
    verify(telegramClient).execute(any(SendPhoto.class));
    verify(telegramClient).execute(any(SendMessage.class));
  }

  @Test
  void should_use_placeholder_when_photo_url_has_javascript_schema() throws TelegramApiException {
    // Given
    var listing = buildListing(11L, "javascript:void(0)", "https://realt.by/11");
    when(wizard.getState(1L)).thenReturn(Optional.of(defaultState));
    when(listingService.search(any(), any())).thenReturn(pageOf(listing));
    when(listingFormatter.buildCaption(listing)).thenReturn("caption");
    when(listingFormatter.buildKeyboard(anyString())).thenReturn(mock(InlineKeyboardMarkup.class));
    lenient().when(telegramClient.execute(any(EditMessageText.class))).thenReturn(mock());
    // Simulate Telegram API rejecting "javascript:void(0)"
    when(telegramClient.execute(any(SendPhoto.class))).thenAnswer(invocation -> {
      SendPhoto photo = invocation.getArgument(0);
      if ("javascript:void(0)".equals(photo.getPhoto().getAttachName())) {
        throw new TelegramApiException("[400] Wrong string length");
      }
      return null;
    });

    // When
    searchResultSender.handle(buildCallback(1L, 100L, 10));

    // Then — placeholder was used, no text card fallback
    verify(telegramClient).execute(any(SendPhoto.class));
    verify(telegramClient).execute(any(SendMessage.class));
  }

  @Test
  void should_use_placeholder_when_photo_url_is_blank_with_spaces() throws TelegramApiException {
    // Given — listing with a whitespace-only URL
    var listing = buildListing(12L, "   ", "https://realt.by/12");
    when(wizard.getState(1L)).thenReturn(Optional.of(defaultState));
    when(listingService.search(any(), any())).thenReturn(pageOf(listing));
    when(listingFormatter.buildCaption(listing)).thenReturn("caption");
    when(listingFormatter.buildKeyboard(anyString())).thenReturn(mock(InlineKeyboardMarkup.class));

    // When
    searchResultSender.handle(buildCallback(1L, 100L, 10));

    // Then — photo card sent with placeholder, no text card fallback
    verify(telegramClient).execute(any(SendPhoto.class));
    verify(telegramClient).execute(any(SendMessage.class));
  }

  // -------------------------------------------------------------------------
  // handleLastSearch
  // -------------------------------------------------------------------------

  @Test
  void should_send_results_when_last_search_has_saved_filter() throws TelegramApiException {
    // Given
    var savedFilter = new SearchFilter(null, "RENT", 42L, null, null, 2, null, "APARTMENT", null, null);
    when(userSavedSearchService.getByTelegramUserId(1L)).thenReturn(Optional.of(savedFilter));
    var listing = buildListing(30L, null, "https://realt.by/30");
    when(listingService.search(any(), any())).thenReturn(pageOf(listing));
    when(listingFormatter.buildCaption(listing)).thenReturn("caption");
    when(listingFormatter.buildKeyboard(anyString())).thenReturn(mock(InlineKeyboardMarkup.class));

    // When
    searchResultSender.handleLastSearch(buildCallback(1L, 100L, 10));

    // Then — SendPhoto for card + SendMessage for navigation
    verify(telegramClient).execute(any(SendPhoto.class));
    verify(telegramClient).execute(any(SendMessage.class));
  }

  @Test
  void should_send_no_saved_filter_message_when_last_search_has_no_filter() throws TelegramApiException {
    // Given
    when(userSavedSearchService.getByTelegramUserId(1L)).thenReturn(Optional.empty());

    // When
    searchResultSender.handleLastSearch(buildCallback(1L, 100L, 10));

    // Then — only one SendMessage with "not found" text; no listing service called
    verify(telegramClient).execute(any(SendMessage.class));
    verifyNoInteractions(listingService);
  }

  @Test
  void should_send_no_results_message_when_last_search_returns_empty() throws TelegramApiException {
    // Given
    var savedFilter = new SearchFilter(null, null, null, null, null, null, null, null, null, null);
    when(userSavedSearchService.getByTelegramUserId(1L)).thenReturn(Optional.of(savedFilter));
    when(listingService.search(any(), any())).thenReturn(Page.empty());

    // When
    searchResultSender.handleLastSearch(buildCallback(1L, 100L, 10));

    // Then — only one SendMessage with "no results" text
    verify(telegramClient).execute(any(SendMessage.class));
    verify(listingFormatter, never()).buildCaption(any());
  }

  // -------------------------------------------------------------------------
  // auto-save filter
  // -------------------------------------------------------------------------

  @Test
  void should_save_filter_when_search_returns_results() throws TelegramApiException {
    // Given
    var state = new SearchFilterState();
    state.setPropertyType("APARTMENT");
    state.setRooms(2);
    state.setPriceMin(BigDecimal.valueOf(500));
    state.setPriceMax(BigDecimal.valueOf(1_500));
    state.setOwnerOnly(true);
    state.setQuery("тихий район");

    var listing = buildListing(20L, null, "https://realt.by/20");
    when(wizard.getState(1L)).thenReturn(Optional.of(state));
    when(listingService.search(any(), any())).thenReturn(pageOf(listing));
    when(listingFormatter.buildCaption(listing)).thenReturn("caption");
    when(listingFormatter.buildKeyboard(anyString())).thenReturn(mock(InlineKeyboardMarkup.class));

    // When
    searchResultSender.handle(buildCallback(1L, 100L, 10));

    // Then — filter saved with values from wizard state (dealType=null, cityId=null — not set in state)
    verify(userSavedSearchService).save(1L, new SearchFilter(
        null, null, null, BigDecimal.valueOf(500), BigDecimal.valueOf(1_500),
        2, null, "APARTMENT", true, "тихий район"
    ));
  }

  @Test
  void should_not_save_filter_when_search_returns_empty() throws TelegramApiException {
    // Given
    when(wizard.getState(1L)).thenReturn(Optional.of(defaultState));
    when(listingService.search(any(), any())).thenReturn(Page.empty());

    // When
    searchResultSender.handle(buildCallback(1L, 100L, 10));

    // Then — no save attempt on empty results
    verify(userSavedSearchService, never()).save(anyLong(), any());
  }

  @Test
  void should_not_interrupt_results_when_save_fails() throws TelegramApiException {
    // Given
    var listing = buildListing(21L, null, "https://realt.by/21");
    when(wizard.getState(1L)).thenReturn(Optional.of(defaultState));
    when(listingService.search(any(), any())).thenReturn(pageOf(listing));
    when(listingFormatter.buildCaption(listing)).thenReturn("caption");
    when(listingFormatter.buildKeyboard(anyString())).thenReturn(mock(InlineKeyboardMarkup.class));
    doThrow(new RuntimeException("DB error")).when(userSavedSearchService).save(anyLong(), any());

    // When / Then — no exception propagated, results are still delivered
    assertThatNoException().isThrownBy(
        () -> searchResultSender.handle(buildCallback(1L, 100L, 10))
    );
    verify(telegramClient).execute(any(SendPhoto.class));
    verify(telegramClient).execute(any(SendMessage.class));
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
        null,
        "realt",
        null,
        photoUrl,
        sourceUrl
    );
  }

  @SafeVarargs
  private static <T> Page<T> pageOf(T... items) {
    return new PageImpl<>(List.of(items), PageRequest.of(0, 3), items.length);
  }

  private static CallbackQuery buildCallback(long userId, long chatId, int messageId) {
    var user = mock(User.class);
    when(user.getId()).thenReturn(userId);

    var message = mock(Message.class);
    when(message.getChatId()).thenReturn(chatId);
    lenient().when(message.getMessageId()).thenReturn(messageId);

    var callback = mock(CallbackQuery.class);
    when(callback.getFrom()).thenReturn(user);
    when(callback.getMessage()).thenReturn(message);
    return callback;
  }
}
