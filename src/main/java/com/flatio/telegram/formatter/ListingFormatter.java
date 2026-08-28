package com.flatio.telegram.formatter;

import com.flatio.common.util.TelegramHtmlEscaper;
import com.flatio.domain.listing.ListingStatus;
import com.flatio.telegram.config.SourceDisplayProperties;
import com.flatio.web.dto.ListingResponse;
import com.flatio.web.dto.ListingSummaryResponse;
import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

/**
 * Formats a {@link ListingSummaryResponse} into a Telegram listing card.
 *
 * <p>Stateless component — safe for concurrent use. Produces an HTML caption (≤ 1 024 chars)
 * and an inline keyboard for use with {@code sendMessage} or {@code sendPhoto}.
 *
 * <p>Caption structure:
 * <ul>
 *   <li>Zone 1 — room-count prefix with price on the first line, address on the second line</li>
 *   <li>Zone 2 — publication time and source badge</li>
 * </ul>
 *
 * <p>First-line format: {@code {N}-комнатная за $X (Y BYN)} when USD price is available,
 * otherwise {@code {N}-комнатная за Y BYN} or {@code {N}-комнатная за Z CURRENCY}.
 *
 * <p>Price display rules:
 * <ul>
 *   <li>When {@code priceUsd} is present: {@code $X (Y BYN)} — USD first, BYN in brackets.</li>
 *   <li>When price is in BYN only: {@code Y BYN}.</li>
 *   <li>Other currencies: {@code amount CURRENCY}.</li>
 * </ul>
 *
 * <p>Per-source display name and the "address not specified" label are read from
 * {@link SourceDisplayProperties} (issue #423) — adding a new source is a configuration change,
 * not a code change here.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ListingFormatter {

  private static final int CAPTION_MAX_LENGTH = 1024;
  private static final String LABEL_NEGOTIABLE = "Договорная";
  private static final String LABEL_ADDRESS_UNKNOWN = "Адрес не указан";
  private static final String LABEL_LISTING_INACTIVE = "❗️Объявление неактуально";
  private static final DateTimeFormatter PUBLISHED_FORMATTER =
      DateTimeFormatter.ofPattern("HH:mm, dd.MM.yyyy").withZone(ZoneId.of("Europe/Minsk"));

  private final SourceDisplayProperties sourceDisplayProperties;

  /**
   * Builds the HTML caption for a listing card.
   *
   * <p>If the caption exceeds 1 024 characters it is hard-clamped to fit.
   *
   * @param listing the listing summary to format, never null
   * @return HTML-formatted caption, at most 1 024 characters, never null
   */
  public String buildCaption(ListingSummaryResponse listing) {
    String caption = assembleCaption(listing);
    if (caption.length() <= CAPTION_MAX_LENGTH) {
      return caption;
    }
    log.warn("Caption exceeds limit ({}), hard-clamping: listingId={}", caption.length(), listing.id());
    return caption.substring(0, CAPTION_MAX_LENGTH - 1) + "…";
  }

  /**
   * Builds the HTML caption for a listing opened via a Telegram deep link (issue #418).
   *
   * <p>Unlike {@link #buildCaption}, this works from the full {@link ListingResponse} returned
   * by a direct by-ID lookup rather than the search-result summary, since a deep link resolves
   * exactly one listing and is not the product of a filtered search. A listing that has been
   * deactivated ({@code status != ACTIVE}) is shown with an "неактуально" label instead of being
   * hidden or treated as an error — the user followed a link to a specific listing and should see
   * what happened to it.
   *
   * @param listing full listing details, never null
   * @return HTML-formatted caption, at most 1 024 characters, never null
   */
  public String buildDeepLinkCaption(ListingResponse listing) {
    var sb = new StringBuilder();
    String roomPrefix = buildRoomTypePrefix(listing.rooms(), listing.propertyType());
    String priceFormatted = listing.priceLabel() != null
        ? "<b>" + listing.priceLabel() + "</b>"
        : formatPrice(listing.price(), listing.currency(), null, null);

    if (roomPrefix.isEmpty()) {
      sb.append(priceFormatted);
    } else {
      sb.append(TelegramHtmlEscaper.escapeHtml(roomPrefix)).append(" за ").append(priceFormatted);
    }

    String address = formatLocation(listing.address(), listing.district(), listing.city());
    if (!address.isEmpty()) {
      sb.append("\n").append(TelegramHtmlEscaper.escapeHtml(address));
    }
    if (listing.status() != ListingStatus.ACTIVE) {
      sb.append("\n\n").append(LABEL_LISTING_INACTIVE);
    }

    String caption = sb.toString().strip();
    if (caption.length() <= CAPTION_MAX_LENGTH) {
      return caption;
    }
    log.warn("Deep-link caption exceeds limit ({}), hard-clamping: listingId={}", caption.length(), listing.id());
    return caption.substring(0, CAPTION_MAX_LENGTH - 1) + "…";
  }

  /**
   * Builds the inline keyboard for a listing card with an "Open listing" URL button.
   *
   * @param sourceUrl the URL to the original listing on the source platform, never null
   * @return InlineKeyboardMarkup with a single URL button, never null
   */
  public InlineKeyboardMarkup buildKeyboard(String sourceUrl) {
    var button = InlineKeyboardButton.builder()
        .text("Открыть объявление →")
        .url(sourceUrl)
        .build();
    return InlineKeyboardMarkup.builder()
        .keyboardRow(new InlineKeyboardRow(button))
        .build();
  }

  private String assembleCaption(ListingSummaryResponse listing) {
    var sb = new StringBuilder();
    appendZone1(sb, listing);
    sb.append("\n\n");
    appendZone3(sb, listing);
    return sb.toString().strip();
  }

  /**
   * Appends zone 1 to the caption builder.
   *
   * <p>Zone 1 always occupies two lines:
   * <ol>
   *   <li>Room-count prefix + price: {@code {N}-комнатная за $USD (BYN BYN)}</li>
   *   <li>Address — uses {@code address} field when present; falls back to district and/or city;
   *       shows "Адрес не указан" for Kufar sources when all three are absent, omitted for others</li>
   * </ol>
   *
   * @param sb      the caption builder to append to, never null
   * @param listing the listing data source, never null
   */
  private void appendZone1(StringBuilder sb, ListingSummaryResponse listing) {
    String roomPrefix = buildRoomTypePrefix(listing.rooms(), listing.propertyType());
    String priceFormatted = Boolean.TRUE.equals(listing.isNegotiable())
        ? "<b>" + LABEL_NEGOTIABLE + "</b>"
        : formatPrice(listing.price(), listing.currency(), listing.priceUsd(), listing.priceByn());

    if (roomPrefix.isEmpty()) {
      sb.append(priceFormatted);
    } else {
      sb.append(TelegramHtmlEscaper.escapeHtml(roomPrefix)).append(" за ").append(priceFormatted);
    }

    String address = formatLocation(listing.address(), listing.district(), listing.city());
    if (!address.isEmpty()) {
      sb.append("\n").append(TelegramHtmlEscaper.escapeHtml(address));
    } else if (isAddressUnknownLabelEnabled(listing.sourceId())) {
      sb.append("\n").append(LABEL_ADDRESS_UNKNOWN);
    }
  }

  private boolean isAddressUnknownLabelEnabled(String sourceId) {
    return sourceDisplayProperties.findBySourceId(sourceId)
        .map(SourceDisplayProperties.Entry::isAddressUnknownLabelEnabled)
        .orElse(false);
  }

  private String buildRoomTypePrefix(Integer rooms, String propertyType) {
    if ("ROOM".equals(propertyType)) {
      return "Комната";
    }
    if ("HOUSE".equals(propertyType)) {
      return rooms != null && rooms > 0 ? rooms + "-комнатный дом" : "Дом";
    }
    if (rooms != null && rooms > 0) {
      return rooms + "-комнатная";
    }
    return "";
  }

  private void appendZone3(StringBuilder sb, ListingSummaryResponse listing) {
    String time = listing.publishedAt() != null
        ? PUBLISHED_FORMATTER.format(listing.publishedAt()) : "";
    String badge = resolveSourceDisplayName(listing.sourceId());
    sb.append("🕐 ").append(time).append("  ·  ").append(badge);
  }

  /**
   * Resolves a human-readable display name for the given source identifier.
   *
   * <p>Looked up from {@link SourceDisplayProperties} by prefix match (case-insensitive) so that
   * all connectors for the same platform (e.g. {@code REALT_FLAT_RENT}, {@code REALT_HOUSE_SALE})
   * map to the same display name.
   *
   * @param sourceId the internal connector source ID, may be null
   * @return human-readable platform name, or capitalised sourceId for unconfigured sources, never null
   */
  private String resolveSourceDisplayName(String sourceId) {
    if (sourceId == null || sourceId.isBlank()) {
      return "";
    }
    return sourceDisplayProperties.findBySourceId(sourceId)
        .map(SourceDisplayProperties.Entry::getDisplayName)
        .orElseGet(() -> capitalize(sourceId));
  }

  /**
   * Formats the price for display.
   *
   * <p>Priority order:
   * <ol>
   *   <li>When {@code priceUsd} is present: source stores in BYN but has USD original —
   *       displays as {@code $priceUsd (price BYN)}.</li>
   *   <li>When {@code currency} is USD and {@code priceByn} is present: source stores in USD
   *       but BYN equivalent is known — displays as {@code $price (priceByn BYN)}.</li>
   *   <li>When {@code currency} is USD and {@code priceByn} is absent: displays as {@code $price}.</li>
   *   <li>When {@code currency} is BYN: displays as {@code price BYN}.</li>
   *   <li>Other currencies: {@code price CURRENCY}.</li>
   * </ol>
   *
   * @param price    stored price in the main currency, never null
   * @param currency stored currency code, may be null
   * @param priceUsd original USD price (for BYN-stored listings), null if not applicable
   * @param priceByn BYN equivalent (for USD-stored listings), null if not available
   * @return formatted HTML price string, never null
   */
  private String formatPrice(BigDecimal price, String currency, BigDecimal priceUsd, BigDecimal priceByn) {
    if (priceUsd != null) {
      return "<b>$" + formatNumber(priceUsd) + " (" + formatNumber(price) + " BYN)</b>";
    }
    if ("USD".equals(currency)) {
      if (priceByn != null) {
        return "<b>$" + formatNumber(price) + " (" + formatNumber(priceByn) + " BYN)</b>";
      }
      return "<b>$" + formatNumber(price) + "</b>";
    }
    if ("BYN".equals(currency)) {
      return "<b>" + formatNumber(price) + " BYN</b>";
    }
    return "<b>" + formatNumber(price) + " " + (currency != null ? currency : "") + "</b>";
  }

  private String formatLocation(String address, String district, String city) {
    if (address != null && !address.isBlank()) {
      return address;
    }
    if (district != null && city != null) {
      return district + ", " + city;
    }
    if (district != null) {
      return district;
    }
    if (city != null) {
      return city;
    }
    return "";
  }

  private String formatNumber(BigDecimal number) {
    BigDecimal stripped = number.stripTrailingZeros();
    if (stripped.scale() <= 0) {
      return String.format(Locale.US, "%,d", stripped.longValueExact())
          .replace(",", " ");
    }
    return stripped.toPlainString();
  }

  private String capitalize(String s) {
    if (s.isEmpty()) {
      return s;
    }
    return Character.toUpperCase(s.charAt(0)) + s.substring(1);
  }
}
