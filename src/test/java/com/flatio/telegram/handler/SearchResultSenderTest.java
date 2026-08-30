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
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.CRC32;
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
import org.telegram.telegrambots.meta.api.objects.ApiResponse;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;
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
  private static final String IMAGE_PROCESS_FAILED_MESSAGE =
      "Error executing SendPhoto query: [400] Bad Request: IMAGE_PROCESS_FAILED";

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
    when(listingService.search(any(), any(), any(), any())).thenReturn(pageOf(listing));
    when(listingFormatter.buildCaption(listing)).thenReturn("caption");
    when(listingFormatter.buildSearchCardKeyboard(anyString(), any(), any())).thenReturn(mock(InlineKeyboardMarkup.class));

    // When
    searchResultSender.handle(buildCallback(1L, 100L, 10));

    // Then — placeholder sent directly by URL, skipping PhotoProxyClient entirely (no real photo)
    ArgumentCaptor<SendPhoto> photoCaptor = ArgumentCaptor.forClass(SendPhoto.class);
    verify(telegramClient).execute(photoCaptor.capture());
    assertThat(photoCaptor.getValue().getPhoto().getAttachName()).isEqualTo(TEST_NO_PHOTO_URL);
    verify(photoProxyClient, never()).download(anyString(), eq(1L));
    verify(telegramClient).execute(any(SendMessage.class));
  }

  // -------------------------------------------------------------------------
  // Blocked-by-user handling (issue #383)
  // -------------------------------------------------------------------------

  @Test
  @SuppressWarnings("unchecked")
  void should_clear_wizard_and_session_when_user_blocked_bot() throws TelegramApiException {
    // Given — real private chat: chatId equals the Telegram user ID
    var listing = buildListing(1L, null, "https://realt.by/1");
    when(wizard.getState(100L)).thenReturn(Optional.of(defaultState));
    when(listingService.search(any(), any(), any(), any())).thenReturn(pageOf(listing));
    when(listingFormatter.buildCaption(listing)).thenReturn("caption");
    when(listingFormatter.buildSearchCardKeyboard(anyString(), any(), any())).thenReturn(mock(InlineKeyboardMarkup.class));
    lenient().when(telegramClient.execute(any(EditMessageText.class))).thenReturn(mock());
    when(telegramClient.execute(any(SendPhoto.class))).thenThrow(buildBlockedException());

    // When
    searchResultSender.handle(buildCallback(100L, 100L, 10));

    // Then — wizard state and the session just stored before the send are both cleared
    verify(wizard).reset(100L);
    var sessions = (Map<Long, SearchSession>) ReflectionTestUtils.getField(searchResultSender, "sessions");
    assertThat(sessions).doesNotContainKey(100L);
  }

  @Test
  @SuppressWarnings("unchecked")
  void should_not_clear_state_when_send_fails_for_reason_other_than_blocked() throws TelegramApiException {
    // Given — a non-403 failure (e.g. PHOTO_INVALID_DIMENSIONS or network error) must not be
    // treated as a block
    var listing = buildListing(1L, null, "https://realt.by/1");
    when(wizard.getState(101L)).thenReturn(Optional.of(defaultState));
    when(listingService.search(any(), any(), any(), any())).thenReturn(pageOf(listing));
    when(listingFormatter.buildCaption(listing)).thenReturn("caption");
    when(listingFormatter.buildSearchCardKeyboard(anyString(), any(), any())).thenReturn(mock(InlineKeyboardMarkup.class));
    lenient().when(telegramClient.execute(any(EditMessageText.class))).thenReturn(mock());
    when(telegramClient.execute(any(SendPhoto.class)))
        .thenThrow(new TelegramApiException("network error"));

    // When
    searchResultSender.handle(buildCallback(101L, 101L, 10));

    // Then — not classified as a block, so wizard/session state is left untouched
    verify(wizard, never()).reset(any());
    var sessions = (Map<Long, SearchSession>) ReflectionTestUtils.getField(searchResultSender, "sessions");
    assertThat(sessions).containsKey(101L);
  }

  private static TelegramApiRequestException buildBlockedException() {
    var response = ApiResponse.builder()
        .ok(false)
        .errorCode(403)
        .errorDescription("Forbidden: bot was blocked by the user")
        .build();
    return new TelegramApiRequestException("Error", response);
  }

  @Test
  void should_send_photo_message_when_photo_url_present() throws TelegramApiException {
    // Given
    var listing = buildListing(2L, "https://cdn.realt.by/photo.jpg", "https://realt.by/2");
    when(wizard.getState(1L)).thenReturn(Optional.of(defaultState));
    when(listingService.search(any(), any(), any(), any())).thenReturn(pageOf(listing));
    when(listingFormatter.buildCaption(listing)).thenReturn("caption");
    when(listingFormatter.buildSearchCardKeyboard(anyString(), any(), any())).thenReturn(mock(InlineKeyboardMarkup.class));

    // When
    searchResultSender.handle(buildCallback(1L, 100L, 10));

    // Then — photo downloaded by our server then uploaded as binary
    verify(photoProxyClient).download("https://cdn.realt.by/photo.jpg", 2L);
    verify(telegramClient).execute(any(SendPhoto.class));
    verify(telegramClient).execute(any(SendMessage.class));
  }

  @Test
  void should_send_placeholder_photo_when_photo_download_returns_empty() throws TelegramApiException {
    // Given — proxy download fails for the listing photo (e.g. source returns a non-image response)
    var listing = buildListing(3L, "https://content.onliner.by/photo.jpg", "https://onliner.by/3");
    when(wizard.getState(1L)).thenReturn(Optional.of(defaultState));
    when(listingService.search(any(), any(), any(), any())).thenReturn(pageOf(listing));
    when(listingFormatter.buildCaption(listing)).thenReturn("caption");
    when(listingFormatter.buildSearchCardKeyboard(anyString(), any(), any())).thenReturn(mock(InlineKeyboardMarkup.class));
    when(photoProxyClient.download(anyString(), eq(3L))).thenReturn(Optional.empty());

    // When
    searchResultSender.handle(buildCallback(1L, 100L, 10));

    // Then — issue #333: placeholder photo used instead of a text-only card;
    // 1 SendPhoto (placeholder), 1 SendMessage (navigation only)
    ArgumentCaptor<SendPhoto> photoCaptor = ArgumentCaptor.forClass(SendPhoto.class);
    verify(telegramClient).execute(photoCaptor.capture());
    assertThat(photoCaptor.getValue().getPhoto().getAttachName()).isEqualTo(TEST_NO_PHOTO_URL);
    verify(telegramClient, times(1)).execute(any(SendMessage.class));
  }

  @Test
  void should_send_text_card_when_photo_download_empty_and_placeholder_send_also_fails()
      throws TelegramApiException {
    // Given — proxy download fails, and Telegram also rejects the placeholder photo
    var listing = buildListing(3L, "https://content.onliner.by/photo.jpg", "https://onliner.by/3");
    when(wizard.getState(1L)).thenReturn(Optional.of(defaultState));
    when(listingService.search(any(), any(), any(), any())).thenReturn(pageOf(listing));
    when(listingFormatter.buildCaption(listing)).thenReturn("caption");
    when(listingFormatter.buildSearchCardKeyboard(anyString(), any(), any())).thenReturn(mock(InlineKeyboardMarkup.class));
    when(photoProxyClient.download(anyString(), eq(3L))).thenReturn(Optional.empty());
    lenient().when(telegramClient.execute(any(EditMessageText.class))).thenReturn(mock());
    when(telegramClient.execute(any(SendPhoto.class)))
        .thenThrow(new TelegramApiException("Placeholder rejected"));

    // When
    searchResultSender.handle(buildCallback(1L, 100L, 10));

    // Then — text card fallback: 2 SendMessage (text card + navigation), 0 successful SendPhoto
    verify(telegramClient).execute(any(SendPhoto.class));
    verify(telegramClient, times(2)).execute(any(SendMessage.class));
  }

  @Test
  void should_send_text_card_when_binary_upload_to_telegram_fails() throws TelegramApiException {
    // Given — download succeeds, but Telegram rejects the binary upload
    var listing = buildListing(4L, "https://cdn.realt.by/photo.jpg", "https://realt.by/4");
    when(wizard.getState(1L)).thenReturn(Optional.of(defaultState));
    when(listingService.search(any(), any(), any(), any())).thenReturn(pageOf(listing));
    when(listingFormatter.buildCaption(listing)).thenReturn("caption");
    when(listingFormatter.buildSearchCardKeyboard(anyString(), any(), any())).thenReturn(mock(InlineKeyboardMarkup.class));
    lenient().when(telegramClient.execute(any(EditMessageText.class))).thenReturn(mock());
    when(telegramClient.execute(any(SendPhoto.class)))
        .thenThrow(new TelegramApiException("Binary upload rejected"));

    // When
    searchResultSender.handle(buildCallback(1L, 100L, 10));

    // Then — text card fallback: 2 SendMessage (text card + navigation)
    verify(telegramClient).execute(any(SendPhoto.class));
    verify(telegramClient, times(2)).execute(any(SendMessage.class));
  }

  // -------------------------------------------------------------------------
  // IMAGE_PROCESS_FAILED handling (issue #444)
  // -------------------------------------------------------------------------

  @Test
  void should_retry_with_reencoded_jpeg_when_image_process_failed() throws TelegramApiException, IOException {
    // Given — a decodable JPEG; Telegram rejects the first attempt, accepts the re-encoded retry
    byte[] jpegBytes = buildJpegBytes(10, 10);
    ReflectionTestUtils.setField(searchResultSender, "maxSendPhotoBytes", (long) jpegBytes.length);
    var listing = buildListing(70L, "https://rms.kufar.by/photo.jpg", "https://kufar.by/70");
    when(wizard.getState(1L)).thenReturn(Optional.of(defaultState));
    when(listingService.search(any(), any(), any(), any())).thenReturn(pageOf(listing));
    when(listingFormatter.buildCaption(listing)).thenReturn("caption");
    when(listingFormatter.buildSearchCardKeyboard(anyString(), any(), any())).thenReturn(mock(InlineKeyboardMarkup.class));
    when(photoProxyClient.download(anyString(), eq(70L))).thenReturn(Optional.of(jpegBytes));
    lenient().when(telegramClient.execute(any(EditMessageText.class))).thenReturn(mock());
    lenient().when(telegramClient.execute(any(SendMessage.class))).thenReturn(mock());
    when(telegramClient.execute(any(SendPhoto.class)))
        .thenThrow(new TelegramApiException(IMAGE_PROCESS_FAILED_MESSAGE))
        .thenReturn(null);

    // When
    searchResultSender.handle(buildCallback(1L, 100L, 10));

    // Then — one retry with re-encoded bytes succeeds; no placeholder or text-card fallback
    verify(telegramClient, times(2)).execute(any(SendPhoto.class));
    verify(telegramClient, times(1)).execute(any(SendMessage.class));
  }

  @Test
  void should_send_placeholder_when_image_process_failed_and_bytes_not_decodable() throws TelegramApiException {
    // Given — download returns bytes that are not a decodable image at all
    var listing = buildListing(71L, "https://rms.kufar.by/broken.jpg", "https://kufar.by/71");
    when(wizard.getState(1L)).thenReturn(Optional.of(defaultState));
    when(listingService.search(any(), any(), any(), any())).thenReturn(pageOf(listing));
    when(listingFormatter.buildCaption(listing)).thenReturn("caption");
    when(listingFormatter.buildSearchCardKeyboard(anyString(), any(), any())).thenReturn(mock(InlineKeyboardMarkup.class));
    when(photoProxyClient.download(anyString(), eq(71L))).thenReturn(Optional.of(DUMMY_PHOTO_BYTES));
    lenient().when(telegramClient.execute(any(EditMessageText.class))).thenReturn(mock());
    lenient().when(telegramClient.execute(any(SendMessage.class))).thenReturn(mock());
    when(telegramClient.execute(any(SendPhoto.class)))
        .thenThrow(new TelegramApiException(IMAGE_PROCESS_FAILED_MESSAGE))
        .thenReturn(null);

    // When
    searchResultSender.handle(buildCallback(1L, 100L, 10));

    // Then — undecodable bytes skip the re-encode retry and go straight to the placeholder
    ArgumentCaptor<SendPhoto> photoCaptor = ArgumentCaptor.forClass(SendPhoto.class);
    verify(telegramClient, times(2)).execute(photoCaptor.capture());
    assertThat(photoCaptor.getAllValues().get(1).getPhoto().getAttachName()).isEqualTo(TEST_NO_PHOTO_URL);
    verify(telegramClient, times(1)).execute(any(SendMessage.class));
  }

  @Test
  void should_send_placeholder_when_image_process_failed_and_photo_exceeds_pixel_limit()
      throws TelegramApiException, IOException {
    // Given — issue #446: photo bytes are a decompression-bomb shape (huge declared dimensions);
    // Telegram rejects the first attempt with IMAGE_PROCESS_FAILED
    byte[] bombPng = buildOversizedDimensionPng(20_000, 20_000);
    ReflectionTestUtils.setField(searchResultSender, "maxSendPhotoBytes", (long) bombPng.length);
    var listing = buildListing(73L, "https://rms.kufar.by/bomb.png", "https://kufar.by/73");
    when(wizard.getState(1L)).thenReturn(Optional.of(defaultState));
    when(listingService.search(any(), any(), any(), any())).thenReturn(pageOf(listing));
    when(listingFormatter.buildCaption(listing)).thenReturn("caption");
    when(listingFormatter.buildSearchCardKeyboard(anyString(), any(), any())).thenReturn(mock(InlineKeyboardMarkup.class));
    when(photoProxyClient.download(anyString(), eq(73L))).thenReturn(Optional.of(bombPng));
    lenient().when(telegramClient.execute(any(EditMessageText.class))).thenReturn(mock());
    lenient().when(telegramClient.execute(any(SendMessage.class))).thenReturn(mock());
    when(telegramClient.execute(any(SendPhoto.class)))
        .thenThrow(new TelegramApiException(IMAGE_PROCESS_FAILED_MESSAGE))
        .thenReturn(null);

    // When
    searchResultSender.handle(buildCallback(1L, 100L, 10));

    // Then — the pixel-limit guard rejects the re-encode before it would allocate a huge buffer;
    // retry is skipped and the placeholder is sent instead, same as an undecodable photo
    ArgumentCaptor<SendPhoto> photoCaptor = ArgumentCaptor.forClass(SendPhoto.class);
    verify(telegramClient, times(2)).execute(photoCaptor.capture());
    assertThat(photoCaptor.getAllValues().get(1).getPhoto().getAttachName()).isEqualTo(TEST_NO_PHOTO_URL);
    verify(telegramClient, times(1)).execute(any(SendMessage.class));
  }

  @Test
  void should_send_placeholder_when_image_process_failed_persists_after_retry()
      throws TelegramApiException, IOException {
    // Given — decodable JPEG, but Telegram rejects both the original and the re-encoded retry
    byte[] jpegBytes = buildJpegBytes(10, 10);
    ReflectionTestUtils.setField(searchResultSender, "maxSendPhotoBytes", (long) jpegBytes.length);
    var listing = buildListing(72L, "https://rms.kufar.by/photo.jpg", "https://kufar.by/72");
    when(wizard.getState(1L)).thenReturn(Optional.of(defaultState));
    when(listingService.search(any(), any(), any(), any())).thenReturn(pageOf(listing));
    when(listingFormatter.buildCaption(listing)).thenReturn("caption");
    when(listingFormatter.buildSearchCardKeyboard(anyString(), any(), any())).thenReturn(mock(InlineKeyboardMarkup.class));
    when(photoProxyClient.download(anyString(), eq(72L))).thenReturn(Optional.of(jpegBytes));
    lenient().when(telegramClient.execute(any(EditMessageText.class))).thenReturn(mock());
    lenient().when(telegramClient.execute(any(SendMessage.class))).thenReturn(mock());
    when(telegramClient.execute(any(SendPhoto.class)))
        .thenThrow(new TelegramApiException(IMAGE_PROCESS_FAILED_MESSAGE))
        .thenThrow(new TelegramApiException(IMAGE_PROCESS_FAILED_MESSAGE))
        .thenReturn(null);

    // When
    searchResultSender.handle(buildCallback(1L, 100L, 10));

    // Then — exactly one retry attempted (original + re-encoded), then placeholder — not text
    ArgumentCaptor<SendPhoto> photoCaptor = ArgumentCaptor.forClass(SendPhoto.class);
    verify(telegramClient, times(3)).execute(photoCaptor.capture());
    assertThat(photoCaptor.getAllValues().get(2).getPhoto().getAttachName()).isEqualTo(TEST_NO_PHOTO_URL);
    verify(telegramClient, times(1)).execute(any(SendMessage.class));
  }

  private static byte[] buildJpegBytes(int width, int height) throws IOException {
    var img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    var baos = new ByteArrayOutputStream();
    ImageIO.write(img, "jpeg", baos);
    return baos.toByteArray();
  }

  /**
   * Builds a real, ImageIO-decodable PNG (tiny actual pixel data) whose IHDR chunk is patched to
   * declare {@code fakeWidth}x{@code fakeHeight} — the classic decompression-bomb shape: a small
   * file with an enormous declared size. {@code ImageReader.getWidth/getHeight} report the
   * patched IHDR value without needing the (untouched, still tiny) IDAT payload to match it.
   */
  private static byte[] buildOversizedDimensionPng(int fakeWidth, int fakeHeight) throws IOException {
    var img = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
    var baos = new ByteArrayOutputStream();
    ImageIO.write(img, "png", baos);
    byte[] png = baos.toByteArray();

    int typeIndex = indexOf(png, "IHDR".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    int dataIndex = typeIndex + 4;
    writeBigEndianInt(png, dataIndex, fakeWidth);
    writeBigEndianInt(png, dataIndex + 4, fakeHeight);

    var crc = new CRC32();
    crc.update(png, typeIndex, 4 + 13); // "IHDR" + 13 bytes of chunk data
    writeBigEndianInt(png, dataIndex + 13, (int) crc.getValue());
    return png;
  }

  private static int indexOf(byte[] haystack, byte[] needle) {
    outer:
    for (int i = 0; i <= haystack.length - needle.length; i++) {
      for (int j = 0; j < needle.length; j++) {
        if (haystack[i + j] != needle[j]) {
          continue outer;
        }
      }
      return i;
    }
    throw new IllegalStateException("Needle not found in PNG fixture");
  }

  private static void writeBigEndianInt(byte[] data, int offset, int value) {
    data[offset] = (byte) (value >>> 24);
    data[offset + 1] = (byte) (value >>> 16);
    data[offset + 2] = (byte) (value >>> 8);
    data[offset + 3] = (byte) value;
  }

  // -------------------------------------------------------------------------
  // detectUnsupportedFormat — magic-byte format sniffing for undecodable
  // ImageIO photos (issue #465)
  // -------------------------------------------------------------------------

  @Test
  void should_detect_webp_when_riff_webp_signature_present() {
    // Given — RIFF container with a WEBP form type at bytes[8..11]
    byte[] bytes = new byte[]{'R', 'I', 'F', 'F', 0x00, 0x00, 0x00, 0x04, 'W', 'E', 'B', 'P'};

    // When
    String result = ReflectionTestUtils.invokeMethod(searchResultSender, "detectUnsupportedFormat", bytes);

    // Then
    assertThat(result).isEqualTo("WebP");
  }

  @Test
  void should_detect_avif_when_isobmff_ftyp_brand_starts_with_avi() {
    // Given — ISOBMFF ftyp box with an AVIF-family major brand
    for (String brand : new String[]{"avif", "avis"}) {
      byte[] bytes = buildFtypBox(brand);

      // When
      String result = ReflectionTestUtils.invokeMethod(searchResultSender, "detectUnsupportedFormat", bytes);

      // Then
      assertThat(result).as("brand=%s", brand).isEqualTo("AVIF (ISOBMFF, brand=" + brand + ")");
    }
  }

  @Test
  void should_detect_heic_heif_when_isobmff_ftyp_brand_matches_known_variant() {
    // Given — ISOBMFF ftyp box with each documented HEIC/HEIF major brand
    for (String brand : new String[]{"heic", "heix", "hevc", "mif1", "msf1"}) {
      byte[] bytes = buildFtypBox(brand);

      // When
      String result = ReflectionTestUtils.invokeMethod(searchResultSender, "detectUnsupportedFormat", bytes);

      // Then
      assertThat(result).as("brand=%s", brand).isEqualTo("HEIC/HEIF (ISOBMFF, brand=" + brand + ")");
    }
  }

  @Test
  void should_detect_unrecognized_isobmff_container_when_ftyp_brand_is_unknown() {
    // Given — a valid ISOBMFF ftyp box, but a major brand not in the AVIF/HEIC lists (e.g. plain MP4)
    byte[] bytes = buildFtypBox("mp42");

    // When
    String result = ReflectionTestUtils.invokeMethod(searchResultSender, "detectUnsupportedFormat", bytes);

    // Then
    assertThat(result).isEqualTo("unrecognized ISOBMFF container (brand=mp42)");
  }

  @Test
  void should_detect_jpeg2000_when_box_based_signature_present() {
    // Given — the real JP2 signature box: 00 00 00 0C 6A 50 20 20 0D 0A 87 0A
    byte[] bytes = new byte[]{
        0x00, 0x00, 0x00, 0x0C, 'j', 'P', 0x20, 0x20, 0x0D, 0x0A, (byte) 0x87, 0x0A
    };

    // When
    String result = ReflectionTestUtils.invokeMethod(searchResultSender, "detectUnsupportedFormat", bytes);

    // Then
    assertThat(result).isEqualTo("JPEG 2000 (JP2)");
  }

  @Test
  void should_detect_jpeg2000_when_raw_codestream_signature_present() {
    // Given — raw J2K codestream SOC marker (0xFF4F), no surrounding box
    byte[] bytes = new byte[]{(byte) 0xFF, 0x4F};

    // When
    String result = ReflectionTestUtils.invokeMethod(searchResultSender, "detectUnsupportedFormat", bytes);

    // Then
    assertThat(result).isEqualTo("JPEG 2000 (raw codestream)");
  }

  @Test
  void should_return_unrecognized_signature_with_hex_when_bytes_match_no_known_format() {
    // Given — well-formed length, but bytes matching none of the known signatures
    byte[] bytes = new byte[]{
        0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77,
        (byte) 0x88, (byte) 0x99, (byte) 0xAA, (byte) 0xBB
    };

    // When
    String result = ReflectionTestUtils.invokeMethod(searchResultSender, "detectUnsupportedFormat", bytes);

    // Then
    assertThat(result).isEqualTo("unrecognized signature (hex=00112233445566778899AABB)");
  }

  @Test
  void should_return_unrecognized_signature_when_bytes_array_is_empty() {
    // Given — issue #465 edge case: nothing to sniff at all
    byte[] bytes = new byte[0];

    // When / Then — no ArrayIndexOutOfBoundsException, graceful empty-hex fallback
    String result = ReflectionTestUtils.invokeMethod(searchResultSender, "detectUnsupportedFormat", bytes);
    assertThat(result).isEqualTo("unrecognized signature (hex=)");
  }

  @Test
  void should_return_unrecognized_signature_when_bytes_shorter_than_any_signature_check() {
    // Given — too short for any 12-byte or 4-byte signature check, and not the 2-byte J2K marker
    byte[] bytes = new byte[]{0x01, 0x02};

    // When / Then — no ArrayIndexOutOfBoundsException
    String result = ReflectionTestUtils.invokeMethod(searchResultSender, "detectUnsupportedFormat", bytes);
    assertThat(result).isEqualTo("unrecognized signature (hex=0102)");
  }

  /**
   * Builds a minimal 12-byte ISOBMFF {@code ftyp} box: a 4-byte box size (arbitrary, unchecked by
   * the production code), the {@code ftyp} box type, and the given 4-character major brand.
   */
  private static byte[] buildFtypBox(String brand) {
    byte[] bytes = new byte[12];
    bytes[0] = 0x00;
    bytes[1] = 0x00;
    bytes[2] = 0x00;
    bytes[3] = 0x1C;
    System.arraycopy("ftyp".getBytes(StandardCharsets.US_ASCII), 0, bytes, 4, 4);
    System.arraycopy(brand.getBytes(StandardCharsets.US_ASCII), 0, bytes, 8, 4);
    return bytes;
  }

  @Test
  void should_send_as_document_when_photo_exceeds_photo_size_limit() throws TelegramApiException {
    // Given — photo bytes exceed maxSendPhotoBytes (100) but are within maxSendDocumentBytes (1000)
    var oversizedBytes = new byte[(int) TEST_MAX_PHOTO_BYTES + 1];
    var listing = buildListing(50L, "https://content.onliner.by/big.jpg", "https://onliner.by/50");
    when(wizard.getState(1L)).thenReturn(Optional.of(defaultState));
    when(listingService.search(any(), any(), any(), any())).thenReturn(pageOf(listing));
    when(listingFormatter.buildCaption(listing)).thenReturn("caption");
    when(listingFormatter.buildSearchCardKeyboard(anyString(), any(), any())).thenReturn(mock(InlineKeyboardMarkup.class));
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
    when(listingService.search(any(), any(), any(), any())).thenReturn(pageOf(listing));
    when(listingFormatter.buildCaption(listing)).thenReturn("caption");
    when(listingFormatter.buildSearchCardKeyboard(anyString(), any(), any())).thenReturn(mock(InlineKeyboardMarkup.class));
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
    when(listingService.search(any(), any(), any(), any())).thenReturn(pageOf(listing));
    when(listingFormatter.buildCaption(listing)).thenReturn("caption");
    when(listingFormatter.buildSearchCardKeyboard(anyString(), any(), any())).thenReturn(mock(InlineKeyboardMarkup.class));
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
  void should_return_empty_when_declared_pixel_count_exceeds_decode_limit() throws IOException {
    // Given — issue #446: small file, PNG header claims a huge width/height (decompression-bomb
    // shape). The real embedded pixel data is tiny — this only works if the pixel-count check
    // reads the header without decoding, exactly what it's meant to prove.
    byte[] bombPng = buildOversizedDimensionPng(20_000, 20_000);

    // When
    Optional<byte[]> result = searchResultSender.compressPhoto(bombPng, 99L);

    // Then — rejected before any pixel buffer is allocated
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
    when(listingService.search(any(), any(), any(), any())).thenReturn(pageOf(listing));
    when(listingFormatter.buildCaption(listing)).thenReturn("caption");
    when(listingFormatter.buildSearchCardKeyboard(anyString(), any(), any())).thenReturn(mock(InlineKeyboardMarkup.class));
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
    when(listingService.search(any(), any(), any(), any())).thenReturn(Page.empty());

    // When
    searchResultSender.handle(buildCallback(1L, 100L, 10));

    // Then
    verify(telegramClient).execute(any(SendMessage.class));
  }

  @Test
  void should_include_change_filters_and_main_menu_buttons_when_handle_returns_empty() throws TelegramApiException {
    // Given
    when(wizard.getState(1L)).thenReturn(Optional.of(defaultState));
    when(listingService.search(any(), any(), any(), any())).thenReturn(Page.empty());
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
    when(listingService.search(any(), any(), any(), any())).thenReturn(Page.empty());
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
    when(listingService.search(any(), any(), any(), any())).thenReturn(Page.empty());
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
    verify(listingService, never()).search(any(), any(), any(), any());
  }

  // -------------------------------------------------------------------------
  // edit message before sending results
  // -------------------------------------------------------------------------

  @Test
  void should_edit_wizard_message_to_searching_before_sending_results() throws TelegramApiException {
    // Given
    var listing = buildListing(5L, null, "https://realt.by/5");
    when(wizard.getState(1L)).thenReturn(Optional.of(defaultState));
    when(listingService.search(any(), any(), any(), any())).thenReturn(pageOf(listing));
    when(listingFormatter.buildCaption(listing)).thenReturn("caption");
    when(listingFormatter.buildSearchCardKeyboard(anyString(), any(), any())).thenReturn(mock(InlineKeyboardMarkup.class));

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
    when(listingService.search(any(), any(), any(), any())).thenReturn(pageOf(listing1, listing2, listing3));
    when(listingFormatter.buildCaption(any())).thenReturn("caption");
    when(listingFormatter.buildSearchCardKeyboard(anyString(), any(), any())).thenReturn(mock(InlineKeyboardMarkup.class));

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
    when(listingService.search(any(), any(), any(), any())).thenReturn(pageOf(listing1, listing2, listing3));
    when(listingFormatter.buildCaption(listing1)).thenThrow(new RuntimeException("unexpected caption error"));
    when(listingFormatter.buildCaption(listing2)).thenReturn("caption");
    when(listingFormatter.buildCaption(listing3)).thenReturn("caption");
    when(listingFormatter.buildSearchCardKeyboard(anyString(), any(), any())).thenReturn(mock(InlineKeyboardMarkup.class));

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
    when(listingService.search(any(), any(), any(), any())).thenReturn(pageOf(listing1, listing2, listing3));
    when(listingFormatter.buildCaption(any())).thenReturn("caption");
    when(listingFormatter.buildSearchCardKeyboard(anyString(), any(), any())).thenReturn(mock(InlineKeyboardMarkup.class));
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
  // photo URL validation — invalid schema goes straight to placeholder, no download attempt
  // -------------------------------------------------------------------------

  @Test
  void should_send_placeholder_directly_when_photo_url_has_no_http_schema() throws TelegramApiException {
    // Given — listing with a short invalid URL "g"
    var listing = buildListing(10L, "g", "https://realt.by/10");
    when(wizard.getState(1L)).thenReturn(Optional.of(defaultState));
    when(listingService.search(any(), any(), any(), any())).thenReturn(pageOf(listing));
    when(listingFormatter.buildCaption(listing)).thenReturn("caption");
    when(listingFormatter.buildSearchCardKeyboard(anyString(), any(), any())).thenReturn(mock(InlineKeyboardMarkup.class));

    // When
    searchResultSender.handle(buildCallback(1L, 100L, 10));

    // Then — placeholder sent by URL directly, no PhotoProxyClient call for "g"
    ArgumentCaptor<SendPhoto> photoCaptor = ArgumentCaptor.forClass(SendPhoto.class);
    verify(telegramClient).execute(photoCaptor.capture());
    assertThat(photoCaptor.getValue().getPhoto().getAttachName()).isEqualTo(TEST_NO_PHOTO_URL);
    verify(photoProxyClient, never()).download(anyString(), eq(10L));
    verify(telegramClient).execute(any(SendMessage.class));
  }

  @Test
  void should_send_placeholder_directly_when_photo_url_has_javascript_schema() throws TelegramApiException {
    // Given
    var listing = buildListing(11L, "javascript:void(0)", "https://realt.by/11");
    when(wizard.getState(1L)).thenReturn(Optional.of(defaultState));
    when(listingService.search(any(), any(), any(), any())).thenReturn(pageOf(listing));
    when(listingFormatter.buildCaption(listing)).thenReturn("caption");
    when(listingFormatter.buildSearchCardKeyboard(anyString(), any(), any())).thenReturn(mock(InlineKeyboardMarkup.class));

    // When
    searchResultSender.handle(buildCallback(1L, 100L, 10));

    // Then — placeholder sent by URL directly, no PhotoProxyClient call for a javascript: URL
    ArgumentCaptor<SendPhoto> photoCaptor = ArgumentCaptor.forClass(SendPhoto.class);
    verify(telegramClient).execute(photoCaptor.capture());
    assertThat(photoCaptor.getValue().getPhoto().getAttachName()).isEqualTo(TEST_NO_PHOTO_URL);
    verify(photoProxyClient, never()).download(anyString(), eq(11L));
    verify(telegramClient).execute(any(SendMessage.class));
  }

  @Test
  void should_send_placeholder_directly_when_photo_url_is_blank_with_spaces() throws TelegramApiException {
    // Given — listing with a whitespace-only URL
    var listing = buildListing(12L, "   ", "https://realt.by/12");
    when(wizard.getState(1L)).thenReturn(Optional.of(defaultState));
    when(listingService.search(any(), any(), any(), any())).thenReturn(pageOf(listing));
    when(listingFormatter.buildCaption(listing)).thenReturn("caption");
    when(listingFormatter.buildSearchCardKeyboard(anyString(), any(), any())).thenReturn(mock(InlineKeyboardMarkup.class));

    // When
    searchResultSender.handle(buildCallback(1L, 100L, 10));

    // Then — placeholder sent by URL directly, no PhotoProxyClient call for a blank URL
    ArgumentCaptor<SendPhoto> photoCaptor = ArgumentCaptor.forClass(SendPhoto.class);
    verify(telegramClient).execute(photoCaptor.capture());
    assertThat(photoCaptor.getValue().getPhoto().getAttachName()).isEqualTo(TEST_NO_PHOTO_URL);
    verify(photoProxyClient, never()).download(anyString(), eq(12L));
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
    when(listingService.search(any(), any(), any(), any())).thenReturn(pageOf(listing));
    when(listingFormatter.buildCaption(listing)).thenReturn("caption");
    when(listingFormatter.buildSearchCardKeyboard(anyString(), any(), any())).thenReturn(mock(InlineKeyboardMarkup.class));

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
    when(listingService.search(any(), any(), any(), any())).thenReturn(Page.empty());

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
    when(listingService.search(any(), any(), any(), any())).thenReturn(pageOf(listing));
    when(listingFormatter.buildCaption(listing)).thenReturn("caption");
    when(listingFormatter.buildSearchCardKeyboard(anyString(), any(), any())).thenReturn(mock(InlineKeyboardMarkup.class));

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
    when(listingService.search(criteriaCaptor.capture(), any(), any(), any())).thenReturn(pageOf(listing));
    when(listingFormatter.buildCaption(listing)).thenReturn("caption");
    when(listingFormatter.buildSearchCardKeyboard(anyString(), any(), any())).thenReturn(mock(InlineKeyboardMarkup.class));

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
    when(listingService.search(any(), any(), any(), any())).thenReturn(Page.empty());

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
    when(listingService.search(any(), any(), any(), any())).thenReturn(pageOf(listing));
    when(listingFormatter.buildCaption(listing)).thenReturn("caption");
    when(listingFormatter.buildSearchCardKeyboard(anyString(), any(), any())).thenReturn(mock(InlineKeyboardMarkup.class));
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
