package com.flatio.telegram.handler;

import com.flatio.service.ListingService;
import com.flatio.service.UserSavedSearchService;
import com.flatio.service.domain.SearchFilter;
import com.flatio.telegram.formatter.ListingFormatter;
import com.flatio.telegram.state.SearchFilterState;
import com.flatio.telegram.state.SearchFilterWizard;
import com.flatio.web.dto.ListingSummaryResponse;
import com.flatio.telegram.callback.FilterCallbackHandler;
import com.flatio.telegram.state.SearchSession;
import com.flatio.web.dto.ListingSearchCriteria;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.ImageIO;
import org.mockito.ArgumentCaptor;
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
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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

  @Mock
  private PhotoProxyClient photoProxyClient;

  @InjectMocks
  private SearchResultSender searchResultSender;

  private SearchFilterState defaultState;

  private static final String TEST_NO_PHOTO_URL = "https://placeholder.test/no-photo.png";
  private static final byte[] DUMMY_PHOTO_BYTES = new byte[]{0x01, 0x02};

  // Size limits intentionally small so tests don't need to allocate large byte arrays.
  private static final long TEST_MAX_PHOTO_BYTES = 100L;
  private static final long TEST_MAX_DOCUMENT_BYTES = 1000L;

  @BeforeEach
  void setUp() {
    defaultState = new SearchFilterState();
    // @Value fields are not injected by Mockito — set them explicitly.
    ReflectionTestUtils.setField(searchResultSender, "noPhotoUrl", TEST_NO_PHOTO_URL);
    ReflectionTestUtils.setField(searchResultSender, "maxSendPhotoBytes", TEST_MAX_PHOTO_BYTES);
    ReflectionTestUtils.setField(searchResultSender, "maxSendDocumentBytes", TEST_MAX_DOCUMENT_BYTES);
    // Default: photo download succeeds for all URLs
    lenient().when(photoProxyClient.download(anyString(), anyLong()))
        .thenReturn(Optional.of(DUMMY_PHOTO_BYTES));
  }

  // -------------------------------------------------------------------------
  // sendCard — photo proxy download
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

    // Then — SendPhoto for the card (placeholder downloaded as binary), SendMessage for navigation only
    verify(photoProxyClient).download(TEST_NO_PHOTO_URL, 1L);
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

    // Then — photo downloaded by our server then uploaded as binary
    verify(photoProxyClient).download("https://cdn.realt.by/photo.jpg", 2L);
    verify(telegramClient).execute(any(SendPhoto.class));
    verify(telegramClient).execute(any(SendMessage.class));
  }

  @Test
  void should_send_text_card_when_photo_download_returns_empty() throws TelegramApiException {
    // Given — proxy download fails for the listing photo
    var listing = buildListing(3L, "https://content.onliner.by/photo.jpg", "https://onliner.by/3");
    when(wizard.getState(1L)).thenReturn(Optional.of(defaultState));
    when(listingService.search(any(), any())).thenReturn(pageOf(listing));
    when(listingFormatter.buildCaption(listing)).thenReturn("caption");
    when(listingFormatter.buildKeyboard(anyString())).thenReturn(mock(InlineKeyboardMarkup.class));
    when(photoProxyClient.download(anyString(), eq(3L))).thenReturn(Optional.empty());

    // When
    searchResultSender.handle(buildCallback(1L, 100L, 10));

    // Then — text card used instead of photo; 2 SendMessage (text card + navigation), 0 SendPhoto
    verify(telegramClient, never()).execute(any(SendPhoto.class));
    verify(telegramClient, times(2)).execute(any(SendMessage.class));
  }

  @Test
  void should_send_text_card_when_binary_upload_to_telegram_fails() throws TelegramApiException {
    // Given — download succeeds, but Telegram rejects the binary upload
    var listing = buildListing(4L, "https://cdn.realt.by/photo.jpg", "https://realt.by/4");
    when(wizard.getState(1L)).thenReturn(Optional.of(defaultState));
    when(listingService.search(any(), any())).thenReturn(pageOf(listing));
    when(listingFormatter.buildCaption(listing)).thenReturn("caption");
    when(listingFormatter.buildKeyboard(anyString())).thenReturn(mock(InlineKeyboardMarkup.class));
    lenient().when(telegramClient.execute(any(EditMessageText.class))).thenReturn(mock());
    when(telegramClient.execute(any(SendPhoto.class)))
        .thenThrow(new TelegramApiException("Binary upload rejected"));

    // When
    searchResultSender.handle(buildCallback(1L, 100L, 10));

    // Then — text card fallback: 2 SendMessage (text card + navigation)
    verify(telegramClient).execute(any(SendPhoto.class));
    verify(telegramClient, times(2)).execute(any(SendMessage.class));
  }

  @Test
  void should_send_as_document_when_photo_exceeds_photo_size_limit() throws TelegramApiException {
    // Given — photo bytes exceed maxSendPhotoBytes (100) but are within maxSendDocumentBytes (1000)
    var oversizedBytes = new byte[(int) TEST_MAX_PHOTO_BYTES + 1];
    var listing = buildListing(50L, "https://content.onliner.by/big.jpg", "https://onliner.by/50");
    when(wizard.getState(1L)).thenReturn(Optional.of(defaultState));
    when(listingService.search(any(), any())).thenReturn(pageOf(listing));
    when(listingFormatter.buildCaption(listing)).thenReturn("caption");
    when(listingFormatter.buildKeyboard(anyString())).thenReturn(mock(InlineKeyboardMarkup.class));
    when(photoProxyClient.download(anyString(), eq(50L))).thenReturn(Optional.of(oversizedBytes));

    // When
    searchResultSender.handle(buildCallback(1L, 100L, 10));

    // Then — SendDocument used instead of SendPhoto; SendMessage for navigation only
    verify(telegramClient, never()).execute(any(SendPhoto.class));
    verify(telegramClient).execute(any(SendDocument.class));
    verify(telegramClient).execute(any(SendMessage.class));
  }

  @Test
  void should_send_text_card_when_photo_exceeds_document_size_limit() throws TelegramApiException {
    // Given — photo bytes exceed both limits → text fallback
    var tooLargeBytes = new byte[(int) TEST_MAX_DOCUMENT_BYTES + 1];
    var listing = buildListing(51L, "https://content.onliner.by/huge.jpg", "https://onliner.by/51");
    when(wizard.getState(1L)).thenReturn(Optional.of(defaultState));
    when(listingService.search(any(), any())).thenReturn(pageOf(listing));
    when(listingFormatter.buildCaption(listing)).thenReturn("caption");
    when(listingFormatter.buildKeyboard(anyString())).thenReturn(mock(InlineKeyboardMarkup.class));
    when(photoProxyClient.download(anyString(), eq(51L))).thenReturn(Optional.of(tooLargeBytes));

    // When
    searchResultSender.handle(buildCallback(1L, 100L, 10));

    // Then — neither SendPhoto nor SendDocument; 2 SendMessage (text card + navigation)
    verify(telegramClient, never()).execute(any(SendPhoto.class));
    verify(telegramClient, never()).execute(any(SendDocument.class));
    verify(telegramClient, times(2)).execute(any(SendMessage.class));
  }

  @Test
  void should_send_text_card_when_document_upload_fails() throws TelegramApiException {
    // Given — photo > photo limit, SendDocument throws
    var oversizedBytes = new byte[(int) TEST_MAX_PHOTO_BYTES + 1];
    var listing = buildListing(52L, "https://content.onliner.by/big.jpg", "https://onliner.by/52");
    when(wizard.getState(1L)).thenReturn(Optional.of(defaultState));
    when(listingService.search(any(), any())).thenReturn(pageOf(listing));
    when(listingFormatter.buildCaption(listing)).thenReturn("caption");
    when(listingFormatter.buildKeyboard(anyString())).thenReturn(mock(InlineKeyboardMarkup.class));
    when(photoProxyClient.download(anyString(), eq(52L))).thenReturn(Optional.of(oversizedBytes));
    lenient().when(telegramClient.execute(any(EditMessageText.class))).thenReturn(mock());
    when(telegramClient.execute(any(SendDocument.class)))
        .thenThrow(new TelegramApiException("Document upload rejected"));

    // When
    searchResultSender.handle(buildCallback(1L, 100L, 10));

    // Then — text card fallback after document failure: 2 SendMessage (text card + navigation)
    verify(telegramClient).execute(any(SendDocument.class));
    verify(telegramClient, never()).execute(any(SendPhoto.class));
    verify(telegramClient, times(2)).execute(any(SendMessage.class));
  }

  // -------------------------------------------------------------------------
  // compressPhoto — image compression
  // -------------------------------------------------------------------------

  @Test
  void should_return_empty_when_bytes_are_not_parseable_as_image() {
    // Given — raw bytes that are not a valid image format
    byte[] notImage = new byte[]{0x00, 0x11, 0x22, 0x33, 0x44, 0x55};

    // When
    Optional<byte[]> result = searchResultSender.compressPhoto(notImage, 99L);

    // Then — graceful failure, no exception
    assertThat(result).isEmpty();
  }

  @Test
  void should_return_empty_when_compressed_size_still_exceeds_limit() throws IOException {
    // Given — valid JPEG; set photo limit to 1 byte (impossible to satisfy via compression)
    var img = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
    var baos = new ByteArrayOutputStream();
    ImageIO.write(img, "jpeg", baos);
    byte[] jpegBytes = baos.toByteArray();
    ReflectionTestUtils.setField(searchResultSender, "maxSendPhotoBytes", 1L);

    // When
    Optional<byte[]> result = searchResultSender.compressPhoto(jpegBytes, 99L);

    // Then — can't compress any JPEG to 1 byte
    assertThat(result).isEmpty();
  }

  @Test
  void should_return_compressed_bytes_within_limit_when_jpeg_is_provided() throws IOException {
    // Given — valid JPEG; set limit to the original file size → compressed version must fit
    var img = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
    for (int y = 0; y < 100; y++) {
      for (int x = 0; x < 100; x++) {
        img.setRGB(x, y, (x * 200 + y * 100) & 0xFFFFFF);
      }
    }
    var baos = new ByteArrayOutputStream();
    ImageIO.write(img, "jpeg", baos);
    byte[] originalJpeg = baos.toByteArray();
    // Limit = original size: a quality=0.7 re-encode is guaranteed to be ≤ original
    ReflectionTestUtils.setField(searchResultSender, "maxSendPhotoBytes", (long) originalJpeg.length);

    // When
    Optional<byte[]> result = searchResultSender.compressPhoto(originalJpeg, 99L);

    // Then — compression succeeds; result fits within limit
    assertThat(result).isPresent();
    assertThat(result.get().length).isLessThanOrEqualTo(originalJpeg.length);
  }

  @Test
  void should_fall_back_to_document_when_photo_is_not_valid_image() throws TelegramApiException {
    // Given — non-JPEG bytes exceed photo limit; compression fails → document fallback
    var notJpegBytes = new byte[(int) TEST_MAX_PHOTO_BYTES + 1];
    var listing = buildListing(60L, "https://cdn.realt.by/bad.bmp", "https://realt.by/60");
    when(wizard.getState(1L)).thenReturn(Optional.of(defaultState));
    when(listingService.search(any(), any())).thenReturn(pageOf(listing));
    when(listingFormatter.buildCaption(listing)).thenReturn("caption");
    when(listingFormatter.buildKeyboard(anyString())).thenReturn(mock(InlineKeyboardMarkup.class));
    when(photoProxyClient.download(anyString(), eq(60L))).thenReturn(Optional.of(notJpegBytes));

    // When
    searchResultSender.handle(buildCallback(1L, 100L, 10));

    // Then — compression fails, document fallback used
    verify(telegramClient, never()).execute(any(SendPhoto.class));
    verify(telegramClient).execute(any(SendDocument.class));
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

  @Test
  void should_include_change_filters_and_main_menu_buttons_when_handle_returns_empty() throws TelegramApiException {
    // Given
    when(wizard.getState(1L)).thenReturn(Optional.of(defaultState));
    when(listingService.search(any(), any())).thenReturn(Page.empty());
    var captor = ArgumentCaptor.forClass(SendMessage.class);

    // When
    searchResultSender.handle(buildCallback(1L, 100L, 10));

    // Then — SendMessage carries keyboard with "Изменить фильтры" and "Главное меню" buttons
    verify(telegramClient).execute(captor.capture());
    var keyboard = (InlineKeyboardMarkup) captor.getValue().getReplyMarkup();
    assertThat(keyboard).isNotNull();
    var allButtons = keyboard.getKeyboard().stream().flatMap(Collection::stream).toList();
    assertThat(allButtons).anySatisfy(btn ->
        assertThat(btn.getCallbackData()).isEqualTo(FilterCallbackHandler.ACTION_SEARCH));
    assertThat(allButtons).anySatisfy(btn ->
        assertThat(btn.getCallbackData()).isEqualTo(SearchResultSender.ACTION_MENU));
  }

  @Test
  void should_include_change_filters_and_main_menu_buttons_when_last_search_returns_empty() throws TelegramApiException {
    // Given
    var savedFilter = new SearchFilter(null, null, null, null, null, null, null, null, null, null);
    when(userSavedSearchService.getByTelegramUserId(1L)).thenReturn(Optional.of(savedFilter));
    when(listingService.search(any(), any())).thenReturn(Page.empty());
    var captor = ArgumentCaptor.forClass(SendMessage.class);

    // When
    searchResultSender.handleLastSearch(buildCallback(1L, 100L, 10));

    // Then — SendMessage carries keyboard with "Изменить фильтры" and "Главное меню" buttons
    verify(telegramClient).execute(captor.capture());
    var keyboard = (InlineKeyboardMarkup) captor.getValue().getReplyMarkup();
    assertThat(keyboard).isNotNull();
    var allButtons = keyboard.getKeyboard().stream().flatMap(Collection::stream).toList();
    assertThat(allButtons).anySatisfy(btn ->
        assertThat(btn.getCallbackData()).isEqualTo(FilterCallbackHandler.ACTION_SEARCH));
    assertThat(allButtons).anySatisfy(btn ->
        assertThat(btn.getCallbackData()).isEqualTo(SearchResultSender.ACTION_MENU));
  }

  @Test
  void should_include_navigation_keyboard_when_page_navigation_returns_empty() throws TelegramApiException {
    // Given — inject an active session to simulate prior search
    var session = new SearchSession(
        new ListingSearchCriteria(null, null, null, null, null, null, null, null, null, null, null),
        0, 5);
    var sessions = new ConcurrentHashMap<Long, SearchSession>();
    sessions.put(1L, session);
    ReflectionTestUtils.setField(searchResultSender, "sessions", sessions);
    when(listingService.search(any(), any())).thenReturn(Page.empty());
    var captor = ArgumentCaptor.forClass(SendMessage.class);

    // When
    searchResultSender.handlePageCallback(buildPageCallback(1L, 100L, SearchResultSender.PAGE_NEXT));

    // Then — SendMessage carries keyboard with navigation buttons
    verify(telegramClient).execute(captor.capture());
    var keyboard = (InlineKeyboardMarkup) captor.getValue().getReplyMarkup();
    assertThat(keyboard).isNotNull();
    var allButtons = keyboard.getKeyboard().stream().flatMap(Collection::stream).toList();
    assertThat(allButtons).anySatisfy(btn ->
        assertThat(btn.getCallbackData()).isEqualTo(FilterCallbackHandler.ACTION_SEARCH));
    assertThat(allButtons).anySatisfy(btn ->
        assertThat(btn.getCallbackData()).isEqualTo(SearchResultSender.ACTION_MENU));
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
    var listing = buildListing(5L, null, "https://realt.by/5");
    when(wizard.getState(1L)).thenReturn(Optional.of(defaultState));
    when(listingService.search(any(), any())).thenReturn(pageOf(listing));
    when(listingFormatter.buildCaption(listing)).thenReturn("caption");
    when(listingFormatter.buildKeyboard(anyString())).thenReturn(mock(InlineKeyboardMarkup.class));

    // When
    searchResultSender.handle(buildCallback(1L, 100L, 10));

    // Then — EditMessageText once (searching indicator), SendPhoto for card (binary), SendMessage for navigation
    verify(telegramClient).execute(any(EditMessageText.class));
    verify(telegramClient).execute(any(SendPhoto.class));
    verify(telegramClient).execute(any(SendMessage.class));
  }

  // -------------------------------------------------------------------------
  // parallel card sending — full page
  // -------------------------------------------------------------------------

  @Test
  void should_send_all_three_cards_when_page_is_full() throws TelegramApiException {
    // Given — a full page of three listings
    var listing1 = buildListing(70L, null, "https://realt.by/70");
    var listing2 = buildListing(71L, null, "https://realt.by/71");
    var listing3 = buildListing(72L, null, "https://realt.by/72");
    when(wizard.getState(1L)).thenReturn(Optional.of(defaultState));
    when(listingService.search(any(), any())).thenReturn(pageOf(listing1, listing2, listing3));
    when(listingFormatter.buildCaption(any())).thenReturn("caption");
    when(listingFormatter.buildKeyboard(anyString())).thenReturn(mock(InlineKeyboardMarkup.class));

    // When
    assertThatNoException().isThrownBy(
        () -> searchResultSender.handle(buildCallback(1L, 100L, 10))
    );

    // Then — all three cards sent as photos + one navigation message
    verify(telegramClient, times(3)).execute(any(SendPhoto.class));
    verify(telegramClient, times(1)).execute(any(SendMessage.class));
  }

  @Test
  void should_deliver_remaining_cards_when_unexpected_exception_escapes_send_card() throws TelegramApiException {
    // Given — buildCaption throws for listing 1; listings 2 and 3 succeed
    // This simulates an unexpected RuntimeException propagating out of sendCard,
    // which is caught by the per-card try-catch inside sendCardsParallel.
    var listing1 = buildListing(73L, null, "https://realt.by/73");
    var listing2 = buildListing(74L, null, "https://realt.by/74");
    var listing3 = buildListing(75L, null, "https://realt.by/75");
    when(wizard.getState(1L)).thenReturn(Optional.of(defaultState));
    when(listingService.search(any(), any())).thenReturn(pageOf(listing1, listing2, listing3));
    when(listingFormatter.buildCaption(listing1)).thenThrow(new RuntimeException("unexpected caption error"));
    when(listingFormatter.buildCaption(listing2)).thenReturn("caption");
    when(listingFormatter.buildCaption(listing3)).thenReturn("caption");
    when(listingFormatter.buildKeyboard(anyString())).thenReturn(mock(InlineKeyboardMarkup.class));

    // When / Then — no exception propagated to caller
    assertThatNoException().isThrownBy(
        () -> searchResultSender.handle(buildCallback(1L, 100L, 10))
    );

    // Listings 2 and 3 still sent; navigation message still sent
    verify(telegramClient, times(2)).execute(any(SendPhoto.class));
    verify(telegramClient, times(1)).execute(any(SendMessage.class));
  }

  // -------------------------------------------------------------------------
  // fault isolation per card
  // -------------------------------------------------------------------------

  @Test
  void should_not_throw_when_telegram_api_fails_for_one_card() throws TelegramApiException {
    // Given — three listings, telegram throws on first SendPhoto (card 1), succeeds for the rest
    var listing1 = buildListing(6L, null, "https://realt.by/6");
    var listing2 = buildListing(7L, null, "https://realt.by/7");
    var listing3 = buildListing(8L, null, "https://realt.by/8");
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
  // photo URL validation — invalid schema resolves to placeholder before download
  // -------------------------------------------------------------------------

  @Test
  void should_download_placeholder_when_photo_url_has_no_http_schema() throws TelegramApiException {
    // Given — listing with a short invalid URL "g"; resolvePhotoUrl replaces it with the placeholder
    var listing = buildListing(10L, "g", "https://realt.by/10");
    when(wizard.getState(1L)).thenReturn(Optional.of(defaultState));
    when(listingService.search(any(), any())).thenReturn(pageOf(listing));
    when(listingFormatter.buildCaption(listing)).thenReturn("caption");
    when(listingFormatter.buildKeyboard(anyString())).thenReturn(mock(InlineKeyboardMarkup.class));

    // When
    searchResultSender.handle(buildCallback(1L, 100L, 10));

    // Then — placeholder URL is downloaded (not "g"), binary photo is sent
    verify(photoProxyClient).download(TEST_NO_PHOTO_URL, 10L);
    verify(telegramClient).execute(any(SendPhoto.class));
    verify(telegramClient).execute(any(SendMessage.class));
  }

  @Test
  void should_download_placeholder_when_photo_url_has_javascript_schema() throws TelegramApiException {
    // Given
    var listing = buildListing(11L, "javascript:void(0)", "https://realt.by/11");
    when(wizard.getState(1L)).thenReturn(Optional.of(defaultState));
    when(listingService.search(any(), any())).thenReturn(pageOf(listing));
    when(listingFormatter.buildCaption(listing)).thenReturn("caption");
    when(listingFormatter.buildKeyboard(anyString())).thenReturn(mock(InlineKeyboardMarkup.class));

    // When
    searchResultSender.handle(buildCallback(1L, 100L, 10));

    // Then — placeholder downloaded and uploaded as binary
    verify(photoProxyClient).download(TEST_NO_PHOTO_URL, 11L);
    verify(telegramClient).execute(any(SendPhoto.class));
    verify(telegramClient).execute(any(SendMessage.class));
  }

  @Test
  void should_download_placeholder_when_photo_url_is_blank_with_spaces() throws TelegramApiException {
    // Given — listing with a whitespace-only URL
    var listing = buildListing(12L, "   ", "https://realt.by/12");
    when(wizard.getState(1L)).thenReturn(Optional.of(defaultState));
    when(listingService.search(any(), any())).thenReturn(pageOf(listing));
    when(listingFormatter.buildCaption(listing)).thenReturn("caption");
    when(listingFormatter.buildKeyboard(anyString())).thenReturn(mock(InlineKeyboardMarkup.class));

    // When
    searchResultSender.handle(buildCallback(1L, 100L, 10));

    // Then — placeholder URL is downloaded; binary photo card sent, no text fallback
    verify(photoProxyClient).download(TEST_NO_PHOTO_URL, 12L);
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
  void should_pass_multi_word_keyword_to_listing_service_criteria() throws TelegramApiException {
    // Given — wizard state with a 3-word keyword containing a city name (AC #305: min 3 words; #304: city via keyword)
    var state = new SearchFilterState();
    state.setQuery("тихий двор Минск");
    var listing = buildListing(40L, null, "https://realt.by/40");
    when(wizard.getState(1L)).thenReturn(Optional.of(state));
    var criteriaCaptor = ArgumentCaptor.forClass(ListingSearchCriteria.class);
    when(listingService.search(criteriaCaptor.capture(), any())).thenReturn(pageOf(listing));
    when(listingFormatter.buildCaption(listing)).thenReturn("caption");
    when(listingFormatter.buildKeyboard(anyString())).thenReturn(mock(InlineKeyboardMarkup.class));

    // When
    searchResultSender.handle(buildCallback(1L, 100L, 10));

    // Then — full multi-word query passed unchanged; city name embedded in keyword routed via FTS
    assertThat(criteriaCaptor.getValue().query()).isEqualTo("тихий двор Минск");
    assertThat(criteriaCaptor.getValue().city()).isNull();
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
        sourceUrl,
        null
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

  private static CallbackQuery buildPageCallback(long userId, long chatId, String data) {
    var callback = buildCallback(userId, chatId, 0);
    when(callback.getData()).thenReturn(data);
    return callback;
  }
}
