package com.flatio.telegram.handler;

import com.flatio.domain.listing.DealType;
import com.flatio.domain.listing.ListingStatus;
import com.flatio.service.ListingService;
import com.flatio.service.UserSavedSearchService;
import com.flatio.service.domain.SearchFilter;
import com.flatio.telegram.command.SearchCommandHandler;
import com.flatio.telegram.callback.FilterCallbackHandler;
import com.flatio.telegram.callback.SubscriptionsCallbackHandler;
import com.flatio.telegram.formatter.ListingFormatter;
import com.flatio.telegram.state.SearchFilterState;
import com.flatio.telegram.state.SearchSession;
import com.flatio.telegram.state.SearchFilterWizard;
import com.flatio.web.dto.ListingSearchCriteria;
import com.flatio.web.dto.ListingSummaryResponse;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.util.ArrayList;
import java.util.Iterator;
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
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

/**
 * Handles search execution and paginated result delivery.
 *
 * <p>Triggered by the {@code FILTER:SEARCH} callback (initial search) and
 * {@code PAGE:NEXT} / {@code PAGE:PREV} callbacks (pagination).
 * Per-user {@link SearchSession} objects track the active criteria and current page.
 * Sessions expire after {@value #SESSION_TTL_MINUTES} minutes of inactivity.
 *
 * <p>Photo cards are sent via {@code sendPhoto}. A configurable placeholder image
 * ({@code telegram.bot.no-photo-url}) is used whenever the listing has no photo URL, or the
 * real photo could not be downloaded, or Telegram rejects the real photo with
 * {@code PHOTO_INVALID_DIMENSIONS}. On {@code IMAGE_PROCESS_FAILED} the photo is first re-encoded
 * as a baseline sRGB JPEG and resent once (issue #444 — Kufar CDN bytes occasionally use a color
 * profile or encoding Telegram's image processor rejects but Java's decoder reads fine); only if
 * that retry also fails does the card fall back to the placeholder. If the placeholder itself also
 * fails to send, the card falls back to a plain text message so the user always receives the
 * listing details.
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
  /** Callback data for returning to the main menu from any state. */
  public static final String ACTION_MENU = "action:menu";

  private static final int PAGE_SIZE = 3;
  private static final long SESSION_TTL_MINUTES = 30;
  private static final Duration SESSION_TTL = Duration.ofMinutes(SESSION_TTL_MINUTES);
  private static final long MAX_SESSIONS = 10_000;

  /** Telegram error code returned when the user has blocked the bot. */
  private static final int ERROR_CODE_BLOCKED = 403;

  /** JPEG quality factor applied during compression (0.0–1.0). */
  private static final float COMPRESS_QUALITY = 0.7f;

  /**
   * JPEG quality factor used when re-encoding a photo after Telegram rejects it with
   * {@code IMAGE_PROCESS_FAILED}. Kept high — the goal here is format normalization, not size
   * reduction, which {@link #handleOversizedPhoto} already handles separately.
   */
  private static final float NORMALIZE_QUALITY = 0.9f;

  /**
   * Upper bound on a decoded image's pixel count (issue #446). Checked from the format header
   * via {@link ImageReader#getWidth}/{@link ImageReader#getHeight} before any full decode — a
   * small file can declare enormous dimensions (a classic decompression-bomb pattern), which
   * would otherwise let {@link #compressPhoto} or {@link #normalizeForTelegram} allocate a huge
   * pixel buffer from an externally-sourced photo. ~50 megapixels comfortably covers any real
   * listing photo (a 6000×5000 DSLR shot is already only 30 MP) while keeping the worst-case
   * {@code BufferedImage} allocation (4 bytes/pixel) around 200 MB.
   */
  private static final long MAX_DECODE_PIXELS = 50_000_000L;

  private static final String SEARCHING_TEXT = "🔍 Ищу объявления...";
  private static final String NO_RESULTS_TEXT =
      "По вашим фильтрам объявлений не найдено.\nПопробуйте изменить параметры поиска.";
  private static final String NO_FILTERS_TEXT =
      "Пожалуйста, сначала настройте фильтры поиска.";
  private static final String SESSION_EXPIRED_TEXT =
      "Поиск устарел. Начните новый поиск.";
  private static final String NO_SAVED_FILTER_TEXT =
      "Сохранённый поиск не найден. Начните новый поиск.";

  @Value("${telegram.bot.no-photo-url:https://placehold.co/800x600/e2e8f0/94a3b8.png}")
  private String noPhotoUrl;

  // Telegram API binary upload limits; configurable for testing.
  @Value("${telegram.bot.max-photo-bytes:10485760}")
  private long maxSendPhotoBytes;

  @Value("${telegram.bot.max-document-bytes:52428800}")
  private long maxSendDocumentBytes;

  private final SearchFilterWizard wizard;
  private final ListingService listingService;
  private final ListingFormatter listingFormatter;
  private final TelegramClient telegramClient;
  private final UserSavedSearchService userSavedSearchService;
  private final PhotoProxyClient photoProxyClient;

  // Caffeine, not a plain ConcurrentHashMap, so an abandoned session is actually evicted instead
  // of occupying memory for the lifetime of the JVM (issue #382). expireAfterAccess mirrors the
  // sliding 30-minute inactivity window this class already documented and enforced manually.
  private final Map<Long, SearchSession> sessions = Caffeine.newBuilder()
      .expireAfterAccess(SESSION_TTL)
      .maximumSize(MAX_SESSIONS)
      .<Long, SearchSession>build()
      .asMap();

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
    var pageable = PageRequest.of(0, PAGE_SIZE,
        Sort.by(Sort.Order.desc("publishedAt").with(Sort.NullHandling.NULLS_LAST)));
    // userId=null: the bot flow only has the caller's Telegram ID here, not their internal user
    // ID — blacklist exclusion (issue #414) is scoped to the REST search API for now.
    var page = listingService.search(criteria, pageable, null, null);

    if (page.isEmpty()) {
      log.debug("No results found: telegramId={}, criteria={}", telegramId, criteria);
      sendNoResultsMessage(chatId);
      return;
    }

    autoSaveFilter(telegramId, stateOpt.get());
    sessions.put(telegramId, new SearchSession(criteria, 0, page.getTotalPages()));
    log.debug("Sending {} result cards: telegramId={}, totalPages={}", page.getNumberOfElements(), telegramId, page.getTotalPages());
    sendCards(chatId, page.getContent());
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

    var pageable = PageRequest.of(newPage, PAGE_SIZE,
        Sort.by(Sort.Order.desc("publishedAt").with(Sort.NullHandling.NULLS_LAST)));
    // userId=null — see handle() above for why the bot flow does not resolve one here.
    var page = listingService.search(session.getCriteria(), pageable, null, null);

    if (page.isEmpty()) {
      sendNoResultsMessage(chatId);
      return;
    }

    session.setCurrentPage(newPage);
    session.setTotalPages(page.getTotalPages());
    session.touch();

    log.debug("Sending page {} of {}: telegramId={}", newPage + 1, page.getTotalPages(), telegramId);
    sendCards(chatId, page.getContent());
    sendNavigationMessage(chatId, newPage, page.getTotalPages());
  }

  /**
   * Sends listing cards sequentially in the order provided.
   *
   * <p>Each card is delivered before the next one starts, guaranteeing that Telegram
   * displays them in the correct order. An error on one card is logged and does not
   * prevent the remaining cards from being sent.
   *
   * @param chatId   Telegram chat identifier
   * @param listings listings to send in order
   */
  private void sendCards(String chatId, List<ListingSummaryResponse> listings) {
    for (ListingSummaryResponse listing : listings) {
      try {
        sendCard(chatId, listing);
      } catch (Exception e) {
        log.error("Unexpected error sending card: listingId={}", listing.id(), e);
      }
    }
  }

  private record PhotoCard(
      String chatId, Long listingId, byte[] bytes,
      String filename, String caption, InlineKeyboardMarkup keyboard, String photoUrl
  ) {}

  private void sendCard(String chatId, ListingSummaryResponse listing) {
    String caption = listingFormatter.buildCaption(listing);
    // buildSearchCardKeyboard (issues #457, #459), not buildKeyboard — adds the favorites/hide
    // action rows on top of the plain "open listing" button used elsewhere (notifications, deep links).
    var keyboard = listingFormatter.buildSearchCardKeyboard(listing.sourceUrl(), listing.id(), listing.sourceId());
    String photoUrl = listing.photoUrl();

    if (!hasUsablePhotoUrl(photoUrl)) {
      // No real photo (missing, blank, or not http/https) — go straight to the configured
      // placeholder without a wasted PhotoProxyClient call. The placeholder itself is sent to
      // Telegram directly by URL (see sendPlaceholderPhoto), never through PhotoProxyClient,
      // which only downloads listing photos and enforces the source-CDN allowlist on them.
      if (photoUrl != null && !photoUrl.isBlank()) {
        log.debug("Invalid photo url, using placeholder: listingId={}, url={}", listing.id(), photoUrl);
      }
      sendPlaceholderPhoto(chatId, caption, keyboard, listing.id());
      return;
    }

    Instant start = Instant.now();
    Optional<byte[]> photoBytes = photoProxyClient.download(photoUrl, listing.id());
    if (photoBytes.isEmpty()) {
      log.warn("Photo download failed, falling back to placeholder: listingId={}, url={}",
          listing.id(), photoUrl);
      sendPlaceholderPhoto(chatId, caption, keyboard, listing.id());
      return;
    }

    byte[] bytes = photoBytes.get();
    var card = new PhotoCard(chatId, listing.id(), bytes, extractPhotoFilename(photoUrl),
        caption, keyboard, photoUrl);
    if (bytes.length > maxSendPhotoBytes) {
      handleOversizedPhoto(card);
    } else {
      sendPhotoBytes(card);
    }
    log.debug("Card sent: listingId={}, size={}bytes, elapsed={}ms",
        listing.id(), bytes.length, Duration.between(start, Instant.now()).toMillis());
  }

  private void handleOversizedPhoto(PhotoCard card) {
    Optional<byte[]> compressed = compressPhoto(card.bytes(), card.listingId());
    if (compressed.isPresent()) {
      sendPhotoBytes(new PhotoCard(card.chatId(), card.listingId(), compressed.get(),
          card.filename(), card.caption(), card.keyboard(), card.photoUrl()));
      return;
    }
    if (card.bytes().length <= maxSendDocumentBytes) {
      log.warn("Compression insufficient or unsupported, sending as document: " +
          "originalSize={}bytes, listingId={}", card.bytes().length, card.listingId());
      sendDocumentBytes(card);
    } else {
      log.warn("Photo too large for both SendPhoto and SendDocument, falling back to text: " +
          "{}bytes, listingId={}", card.bytes().length, card.listingId());
      sendTextCard(card.chatId(), card.caption(), card.keyboard());
    }
  }

  /**
   * Attempts to compress image bytes to fit within {@link #maxSendPhotoBytes}.
   *
   * <p>Two-stage strategy:
   * <ol>
   *   <li>JPEG quality reduction to {@link #COMPRESS_QUALITY} (0.7).</li>
   *   <li>Proportional dimension scaling if quality reduction is insufficient.</li>
   * </ol>
   *
   * <p>Returns {@link Optional#empty()} when the input is not a supported image format,
   * or when neither strategy reduces the size below the limit.
   * Package-private for unit-testing.
   *
   * @param original    raw image bytes, never null
   * @param listingId   used only for debug logging
   * @return compressed bytes within limit, or empty if compression was insufficient
   */
  Optional<byte[]> compressPhoto(byte[] original, Long listingId) {
    if (!isWithinDecodeLimits(original, listingId)) {
      return Optional.empty();
    }
    try {
      BufferedImage img = ImageIO.read(new ByteArrayInputStream(original));
      if (img == null) {
        log.debug("Image format not supported for compression, skipping: listingId={}", listingId);
        return Optional.empty();
      }

      byte[] qualityBytes = encodeJpeg(img, COMPRESS_QUALITY);
      log.debug("Compression — quality pass: original={}b, after={}b, listingId={}",
          original.length, qualityBytes.length, listingId);
      if (qualityBytes.length <= maxSendPhotoBytes) {
        return Optional.of(qualityBytes);
      }

      double scale = Math.sqrt((double) maxSendPhotoBytes / qualityBytes.length);
      int newW = Math.max(1, (int) (img.getWidth() * scale));
      int newH = Math.max(1, (int) (img.getHeight() * scale));
      var scaled = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_RGB);
      scaled.createGraphics().drawImage(
          img.getScaledInstance(newW, newH, Image.SCALE_SMOOTH), 0, 0, null);

      byte[] scaledBytes = encodeJpeg(scaled, COMPRESS_QUALITY);
      log.debug("Compression — scale pass: original={}b, newDims={}x{}, after={}b, listingId={}",
          original.length, newW, newH, scaledBytes.length, listingId);
      if (scaledBytes.length <= maxSendPhotoBytes) {
        return Optional.of(scaledBytes);
      }

      log.debug("Compression insufficient after scale pass: size={}b, listingId={}",
          scaledBytes.length, listingId);
      return Optional.empty();

    } catch (Exception e) {
      log.debug("Photo compression error: listingId={}, error={}", listingId, e.getMessage());
      return Optional.empty();
    }
  }

  /**
   * Checks an image's declared pixel dimensions from its format header, without decoding pixel
   * data (issue #446) — protects {@link #compressPhoto} and {@link #normalizeForTelegram} from a
   * decompression-bomb photo (small file, enormous claimed dimensions).
   *
   * <p>This is also the check that fails when {@link ImageIO} has no {@link ImageReader} at all
   * for the given bytes (issue #465) — see {@link #detectUnsupportedFormat} for the diagnostic
   * fallback used in that case.
   *
   * @param bytes     raw image bytes, never null
   * @param listingId used only for logging
   * @return true if the format is recognized and its pixel count is within {@link #MAX_DECODE_PIXELS}
   */
  private boolean isWithinDecodeLimits(byte[] bytes, Long listingId) {
    try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
      if (iis == null) {
        return false;
      }
      Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
      if (!readers.hasNext()) {
        return false;
      }
      ImageReader reader = readers.next();
      try {
        reader.setInput(iis, true, true);
        long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
        if (pixels > MAX_DECODE_PIXELS) {
          log.warn("Photo exceeds decode pixel limit, skipping: pixels={}, limit={}, listingId={}",
              pixels, MAX_DECODE_PIXELS, listingId);
          return false;
        }
        return true;
      } finally {
        reader.dispose();
      }
    } catch (Exception e) {
      log.debug("Failed to read image dimensions: listingId={}, error={}", listingId, e.getMessage());
      return false;
    }
  }

  private byte[] encodeJpeg(BufferedImage img, float quality) throws IOException {
    var writers = ImageIO.getImageWritersByFormatName("jpeg");
    if (!writers.hasNext()) {
      throw new IOException("No JPEG ImageWriter available in this JVM");
    }
    ImageWriter writer = writers.next();
    ImageWriteParam param = writer.getDefaultWriteParam();
    param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
    param.setCompressionQuality(quality);
    var baos = new ByteArrayOutputStream();
    try (ImageOutputStream ios = ImageIO.createImageOutputStream(baos)) {
      writer.setOutput(ios);
      writer.write(null, new IIOImage(img, null, null), param);
    } finally {
      writer.dispose();
    }
    return baos.toByteArray();
  }

  private void sendPhotoBytes(PhotoCard card) {
    sendPhotoBytes(card, false);
  }

  private void sendPhotoBytes(PhotoCard card, boolean isRetryAfterNormalize) {
    try {
      telegramClient.execute(SendPhoto.builder()
          .chatId(card.chatId())
          .photo(new InputFile(new ByteArrayInputStream(card.bytes()), card.filename()))
          .caption(card.caption())
          .parseMode("HTML")
          .replyMarkup(card.keyboard())
          .build());
    } catch (TelegramApiException e) {
      handleSendPhotoFailure(card, e, isRetryAfterNormalize);
    }
  }

  private void handleSendPhotoFailure(PhotoCard card, TelegramApiException e, boolean isRetryAfterNormalize) {
    if (isBlockedByUser(e)) {
      handleBlockedByUser(card.chatId());
    } else if (isInvalidDimensions(e)) {
      log.warn("Photo rejected: PHOTO_INVALID_DIMENSIONS, retrying with placeholder: listingId={}, url={}",
          card.listingId(), card.photoUrl());
      sendPlaceholderPhoto(card.chatId(), card.caption(), card.keyboard(), card.listingId());
    } else if (isImageProcessFailed(e) && !isRetryAfterNormalize) {
      retryWithNormalizedImage(card);
    } else if (isImageProcessFailed(e)) {
      log.warn("Photo rejected after re-encode: IMAGE_PROCESS_FAILED, using placeholder: listingId={}, url={}",
          card.listingId(), card.photoUrl());
      sendPlaceholderPhoto(card.chatId(), card.caption(), card.keyboard(), card.listingId());
    } else {
      log.warn("Failed to send binary photo, falling back to text: listingId={}, url={}",
          card.listingId(), card.photoUrl(), e);
      sendTextCard(card.chatId(), card.caption(), card.keyboard());
    }
  }

  /**
   * Re-encodes the photo as a baseline sRGB JPEG and resends it once (issue #444). Falls back to
   * the placeholder immediately when the bytes cannot be decoded at all, since a retry would fail
   * the same way.
   *
   * <p><b>Known limitation (issue #465):</b> when the bytes cannot be decoded, the WARN log below
   * includes a best-effort format guess from {@link #detectUnsupportedFormat} — a magic-byte
   * sniff, not a real decode. This is a diagnostic aid only: at the time this was written, the
   * source URL from the triggering report was unreachable (Kufar CDN did not respond) and no
   * production log access was available to establish how often this occurs, so neither the exact
   * format nor the frequency could be confirmed against real bytes. If a future occurrence's
   * detected format turns out to be a genuinely decodable one (e.g. WebP, AVIF), adding real
   * decode support requires a new {@code ImageIO} plugin dependency (e.g. TwelveMonkeys or
   * webp-imageio), which needs explicit product-owner approval per project rules and is
   * intentionally not added here.
   *
   * @param card the photo card whose bytes were rejected with {@code IMAGE_PROCESS_FAILED}
   */
  private void retryWithNormalizedImage(PhotoCard card) {
    Optional<byte[]> normalized = normalizeForTelegram(card.bytes(), card.listingId());
    if (normalized.isEmpty()) {
      log.warn("Photo rejected: IMAGE_PROCESS_FAILED, re-encode unsupported, using placeholder: "
              + "listingId={}, url={}, detectedFormat={}",
          card.listingId(), card.photoUrl(), detectUnsupportedFormat(card.bytes()));
      sendPlaceholderPhoto(card.chatId(), card.caption(), card.keyboard(), card.listingId());
      return;
    }
    log.debug("Photo rejected: IMAGE_PROCESS_FAILED, retrying with re-encoded JPEG: listingId={}",
        card.listingId());
    sendPhotoBytes(new PhotoCard(card.chatId(), card.listingId(), normalized.get(),
        card.filename(), card.caption(), card.keyboard(), card.photoUrl()), true);
  }

  /**
   * Decodes and re-encodes image bytes as a plain baseline RGB JPEG, dropping any source color
   * profile or encoding quirk (e.g. CMYK JPEG) that Telegram's image processor may reject.
   *
   * @param original  raw image bytes, never null
   * @param listingId used only for debug logging
   * @return re-encoded JPEG bytes, or empty if the input is not a decodable image format
   */
  private Optional<byte[]> normalizeForTelegram(byte[] original, Long listingId) {
    if (!isWithinDecodeLimits(original, listingId)) {
      return Optional.empty();
    }
    try {
      BufferedImage img = ImageIO.read(new ByteArrayInputStream(original));
      if (img == null) {
        return Optional.empty();
      }
      var rgb = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
      rgb.createGraphics().drawImage(img, 0, 0, null);
      return Optional.of(encodeJpeg(rgb, NORMALIZE_QUALITY));
    } catch (Exception e) {
      log.debug("Photo normalization error: listingId={}, error={}", listingId, e.getMessage());
      return Optional.empty();
    }
  }

  /**
   * Best-effort identification of an image's format from its leading magic bytes, used purely for
   * diagnostics when {@link ImageIO} could not find any {@link ImageReader} for the bytes
   * (issue #465). This is a signature sniff, not a decoder — it recognizes container markers for
   * formats the JVM's built-in {@code ImageIO} plugins do not read out of the box (WebP,
   * AVIF/HEIC via ISOBMFF {@code ftyp}, JPEG 2000), which are the most likely culprits for a
   * {@code .jpg}-named CDN file that Java cannot decode.
   *
   * <p><b>Known limitation:</b> it cannot verify the guess by actually decoding the bytes, cannot
   * distinguish sub-variants (e.g. lossy vs. lossless WebP), and reports "unrecognized signature"
   * for anything else, including plain data corruption/truncation.
   *
   * @param bytes raw image bytes that {@link ImageIO} failed to find a reader for, never null
   * @return a short human-readable format guess, or {@code "unrecognized signature (hex=...)"}
   *     with the leading bytes in hex if no known marker matches
   */
  private String detectUnsupportedFormat(byte[] bytes) {
    if (bytes.length >= 12 && matchesAscii(bytes, 0, "RIFF") && matchesAscii(bytes, 8, "WEBP")) {
      return "WebP";
    }
    if (bytes.length >= 12 && matchesAscii(bytes, 4, "ftyp")) {
      String brand = new String(bytes, 8, 4, StandardCharsets.US_ASCII);
      if (brand.startsWith("avi")) {
        return "AVIF (ISOBMFF, brand=" + brand + ")";
      }
      if (brand.startsWith("hei") || brand.startsWith("hev") || brand.equals("mif1") || brand.equals("msf1")) {
        return "HEIC/HEIF (ISOBMFF, brand=" + brand + ")";
      }
      return "unrecognized ISOBMFF container (brand=" + brand + ")";
    }
    if (bytes.length >= 12 && bytes[0] == 0x00 && bytes[1] == 0x00 && bytes[2] == 0x00 && bytes[3] == 0x0C
        && matchesAscii(bytes, 4, "jP  ")) {
      return "JPEG 2000 (JP2)";
    }
    if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0x4F) {
      return "JPEG 2000 (raw codestream)";
    }
    return "unrecognized signature (hex=" + toHexPrefix(bytes) + ")";
  }

  private boolean matchesAscii(byte[] bytes, int offset, String ascii) {
    for (int i = 0; i < ascii.length(); i++) {
      if (bytes[offset + i] != (byte) ascii.charAt(i)) {
        return false;
      }
    }
    return true;
  }

  private String toHexPrefix(byte[] bytes) {
    int len = Math.min(bytes.length, 12);
    var hex = new StringBuilder();
    for (int i = 0; i < len; i++) {
      hex.append(String.format("%02X", bytes[i]));
    }
    return hex.toString();
  }

  private boolean isInvalidDimensions(TelegramApiException e) {
    return e.getMessage() != null && e.getMessage().contains("PHOTO_INVALID_DIMENSIONS");
  }

  private boolean isImageProcessFailed(TelegramApiException e) {
    return e.getMessage() != null && e.getMessage().contains("IMAGE_PROCESS_FAILED");
  }

  private void sendPlaceholderPhoto(String chatId, String caption, InlineKeyboardMarkup keyboard, Long listingId) {
    try {
      telegramClient.execute(SendPhoto.builder()
          .chatId(chatId)
          .photo(new InputFile(noPhotoUrl))
          .caption(caption)
          .parseMode("HTML")
          .replyMarkup(keyboard)
          .build());
    } catch (TelegramApiException e) {
      if (isBlockedByUser(e)) {
        handleBlockedByUser(chatId);
        return;
      }
      log.warn("Failed to send placeholder photo, falling back to text: listingId={}", listingId, e);
      sendTextCard(chatId, caption, keyboard);
    }
  }

  private void sendDocumentBytes(PhotoCard card) {
    try {
      telegramClient.execute(SendDocument.builder()
          .chatId(card.chatId())
          .document(new InputFile(new ByteArrayInputStream(card.bytes()), card.filename()))
          .caption(card.caption())
          .parseMode("HTML")
          .replyMarkup(card.keyboard())
          .build());
    } catch (TelegramApiException e) {
      if (isBlockedByUser(e)) {
        handleBlockedByUser(card.chatId());
        return;
      }
      log.warn("Failed to send binary document, falling back to text: listingId={}, url={}",
          card.listingId(), card.photoUrl(), e);
      sendTextCard(card.chatId(), card.caption(), card.keyboard());
    }
  }

  private String extractPhotoFilename(String url) {
    int slash = url.lastIndexOf('/');
    int query = url.indexOf('?');
    String name = slash >= 0
        ? (query > slash ? url.substring(slash + 1, query) : url.substring(slash + 1))
        : "photo.jpg";
    return name.isBlank() ? "photo.jpg" : name;
  }

  private boolean hasUsablePhotoUrl(String url) {
    return url != null && (url.startsWith("http://") || url.startsWith("https://"));
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
      if (isBlockedByUser(e)) {
        handleBlockedByUser(chatId);
        return;
      }
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
    // Subscribe-to-this-search entry point (issue #458) — only meaningful here since this method
    // is only called after a non-empty result page, i.e. there is an active filter to subscribe to.
    var subscribeBtn = navBtn("🔔 Подписаться на этот поиск", SubscriptionsCallbackHandler.CREATE_FROM_FILTER);

    var markupBuilder = InlineKeyboardMarkup.builder();
    if (!navButtons.isEmpty()) {
      markupBuilder.keyboardRow(new InlineKeyboardRow(navButtons));
    }
    markupBuilder.keyboardRow(new InlineKeyboardRow(subscribeBtn));
    markupBuilder.keyboardRow(new InlineKeyboardRow(newSearchBtn));

    try {
      telegramClient.execute(SendMessage.builder()
          .chatId(chatId)
          .text(pageText)
          .replyMarkup(markupBuilder.build())
          .build());
    } catch (TelegramApiException e) {
      if (isBlockedByUser(e)) {
        handleBlockedByUser(chatId);
        return;
      }
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
      if (isBlockedByUser(e)) {
        handleBlockedByUser(chatId);
        return;
      }
      log.warn("Failed to edit wizard message to searching state: chatId={}", chatId, e);
    }
  }

  private void sendNoResultsMessage(String chatId) {
    var changeFiltersBtn = navBtn("🔍 Изменить фильтры", FilterCallbackHandler.ACTION_SEARCH);
    var mainMenuBtn = navBtn("🏠 Главное меню", ACTION_MENU);
    var keyboard = InlineKeyboardMarkup.builder()
        .keyboardRow(new InlineKeyboardRow(changeFiltersBtn))
        .keyboardRow(new InlineKeyboardRow(mainMenuBtn))
        .build();
    try {
      telegramClient.execute(SendMessage.builder()
          .chatId(chatId)
          .text(NO_RESULTS_TEXT)
          .replyMarkup(keyboard)
          .build());
    } catch (TelegramApiException e) {
      if (isBlockedByUser(e)) {
        handleBlockedByUser(chatId);
        return;
      }
      log.error("Failed to send no-results message: chatId={}", chatId, e);
    }
  }

  private void sendText(String chatId, String text) {
    try {
      telegramClient.execute(SendMessage.builder()
          .chatId(chatId)
          .text(text)
          .build());
    } catch (TelegramApiException e) {
      if (isBlockedByUser(e)) {
        handleBlockedByUser(chatId);
        return;
      }
      log.error("Failed to send text message: chatId={}", chatId, e);
    }
  }

  /**
   * Looks up the active session for a user, or null if one was never started or has expired.
   *
   * <p>Expiry itself is enforced by the {@link #sessions} cache's {@code expireAfterAccess}
   * policy — a stale entry is already absent by the time this reads it, so no manual TTL check
   * is needed here.
   *
   * @param telegramId Telegram user identifier, never null
   * @return the active session, or null
   */
  /**
   * Checks whether a {@link TelegramApiException} is Telegram's "bot was blocked by the user"
   * error (HTTP 403), as opposed to a genuine delivery failure.
   *
   * @param e the exception caught while calling the Telegram API
   * @return true if the user has blocked the bot
   */
  private boolean isBlockedByUser(TelegramApiException e) {
    return e instanceof TelegramApiRequestException re
        && Integer.valueOf(ERROR_CODE_BLOCKED).equals(re.getErrorCode());
  }

  /**
   * Clears wizard and search-session state for a user who has blocked the bot (issue #383).
   *
   * <p>Logged at DEBUG, not ERROR/WARN — a user blocking the bot is routine behaviour, not an
   * incident, and treating it as one drowns out genuine delivery failures in monitoring.
   *
   * @param chatId the chat the send failed for; in this bot's private 1:1 chats this equals the
   *               Telegram user ID
   */
  private void handleBlockedByUser(String chatId) {
    log.debug("Bot blocked by user, clearing wizard/session state: chatId={}", chatId);
    try {
      Long telegramId = Long.valueOf(chatId);
      wizard.reset(telegramId);
      sessions.remove(telegramId);
    } catch (NumberFormatException e) {
      log.debug("Non-numeric chatId, skipping wizard/session cleanup: chatId={}", chatId);
    }
  }

  private SearchSession getActiveSession(Long telegramId) {
    SearchSession session = sessions.get(telegramId);
    if (session == null) {
      log.debug("Search session not found or expired: telegramId={}", telegramId);
    }
    return session;
  }

  /**
   * Executes the search immediately using the user's last saved filter.
   *
   * <p>Triggered by the {@code action:use-last-search} callback. Skips the filter wizard and
   * runs the search directly with the filter stored in {@code user_saved_searches}.
   * If no saved filter is found for the user, a prompt to start a new search is sent instead.
   *
   * @param callbackQuery the incoming callback query, never null
   */
  public void handleLastSearch(CallbackQuery callbackQuery) {
    Long telegramId = callbackQuery.getFrom().getId();
    String chatId = String.valueOf(callbackQuery.getMessage().getChatId());

    var filterOpt = userSavedSearchService.getByTelegramUserId(telegramId);
    if (filterOpt.isEmpty()) {
      log.debug("No saved filter for last-search: telegramId={}", telegramId);
      sendText(chatId, NO_SAVED_FILTER_TEXT);
      return;
    }

    var criteria = buildCriteriaFromFilter(filterOpt.get());
    var pageable = PageRequest.of(0, PAGE_SIZE,
        Sort.by(Sort.Order.desc("publishedAt").with(Sort.NullHandling.NULLS_LAST)));
    // userId=null — see handle() above for why the bot flow does not resolve one here.
    var page = listingService.search(criteria, pageable, null, null);

    if (page.isEmpty()) {
      log.debug("No results for last-search: telegramId={}", telegramId);
      sendNoResultsMessage(chatId);
      return;
    }

    sessions.put(telegramId, new SearchSession(criteria, 0, page.getTotalPages()));
    log.debug("Sending {} last-search cards: telegramId={}, totalPages={}",
        page.getNumberOfElements(), telegramId, page.getTotalPages());
    sendCards(chatId, page.getContent());
    sendNavigationMessage(chatId, 0, page.getTotalPages());
  }

  /**
   * Removes the active search session for a user, if any.
   *
   * <p>Called by {@link FlatioBot} when a send to this user fails with the Telegram "bot was
   * blocked by the user" error (issue #383) — the session is stale the moment the bot can no
   * longer reach the user, so there is no reason to wait out the TTL.
   *
   * @param telegramId Telegram user identifier, never null
   */
  public void clearSession(Long telegramId) {
    sessions.remove(telegramId);
  }

  private void autoSaveFilter(Long telegramId, SearchFilterState state) {
    try {
      var dealTypeName = state.getDealType() != null ? state.getDealType().name() : null;
      var filter = new SearchFilter(
          null,
          dealTypeName,
          state.getCityId(),
          state.getPriceMin(),
          state.getPriceMax(),
          state.getRooms(),
          null,
          state.getPropertyType(),
          state.getOwnerOnly(),
          state.getQuery()
      );
      userSavedSearchService.save(telegramId, filter);
    } catch (Exception e) {
      log.error("Failed to auto-save search filter: telegramId={}", telegramId, e);
    }
  }

  private ListingSearchCriteria buildCriteria(SearchFilterState state) {
    return new ListingSearchCriteria(
        state.getDealType(),
        state.getPropertyType(),
        null,
        null,
        state.getCityId(),
        state.getPriceMin(),
        state.getPriceMax(),
        state.getRooms(),
        ListingStatus.ACTIVE,
        state.getQuery(),
        state.getOwnerOnly()
    );
  }

  private ListingSearchCriteria buildCriteriaFromFilter(SearchFilter filter) {
    var dealType = filter.dealType() != null ? DealType.valueOf(filter.dealType()) : null;
    return new ListingSearchCriteria(
        dealType,
        filter.propertyType(),
        null,
        null,
        filter.cityId(),
        filter.priceMin(),
        filter.priceMax(),
        filter.roomsMin(),
        ListingStatus.ACTIVE,
        filter.keyword(),
        filter.isOwner()
    );
  }
}
