package com.flatio.telegram.formatter;

import com.flatio.web.dto.ListingSummaryResponse;
import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
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
 *   <li>Zone 1 — title and price</li>
 *   <li>Zone 2 — location and area (omitted on truncation)</li>
 *   <li>Zone 3 — publication time and source badge</li>
 * </ul>
 */
@Component
@Slf4j
public class ListingFormatter {

  private static final int CAPTION_MAX_LENGTH = 1024;
  private static final DateTimeFormatter PUBLISHED_FORMATTER =
      DateTimeFormatter.ofPattern("HH:mm, dd.MM.yyyy").withZone(ZoneId.of("Europe/Minsk"));
  private static final Map<String, String> SOURCE_BADGES = Map.of(
      "kufar", "Kufar",
      "onliner", "Onliner",
      "realt", "Realt.by"
  );

  /**
   * Builds the HTML caption for a listing card.
   *
   * <p>If the full caption exceeds 1 024 characters the location/area zone is omitted
   * to ensure the title, price, and meta zone always fit.
   *
   * @param listing the listing summary to format, never null
   * @return HTML-formatted caption, at most 1 024 characters, never null
   */
  public String buildCaption(ListingSummaryResponse listing) {
    String full = assembleCaption(listing, true);
    if (full.length() <= CAPTION_MAX_LENGTH) {
      return full;
    }
    log.debug("Caption exceeds limit ({}), dropping zone 2: listingId={}", full.length(), listing.id());
    return assembleCaption(listing, false);
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

  private String assembleCaption(ListingSummaryResponse listing, boolean includeZone2) {
    var sb = new StringBuilder();
    appendZone1(sb, listing);
    if (includeZone2) {
      String zone2 = buildZone2(listing);
      if (!zone2.isEmpty()) {
        sb.append("\n\n").append(zone2);
      }
    }
    sb.append("\n\n");
    appendZone3(sb, listing);
    return sb.toString().strip();
  }

  private void appendZone1(StringBuilder sb, ListingSummaryResponse listing) {
    sb.append("<b>").append(escapeHtml(listing.title())).append("</b>");
    sb.append("\n");
    sb.append(formatPrice(listing.price(), listing.currency()));
  }

  private String buildZone2(ListingSummaryResponse listing) {
    var sb = new StringBuilder();
    String location = formatLocation(listing.district(), listing.city());
    if (!location.isEmpty()) {
      sb.append("📍 ").append(escapeHtml(location));
    }
    if (listing.areaTotalM2() != null) {
      if (sb.length() > 0) {
        sb.append("\n");
      }
      sb.append("📐 ").append(formatArea(listing.areaTotalM2()));
    }
    return sb.toString();
  }

  private void appendZone3(StringBuilder sb, ListingSummaryResponse listing) {
    String time = listing.publishedAt() != null
        ? PUBLISHED_FORMATTER.format(listing.publishedAt()) : "";
    String badge = listing.sourceId() != null
        ? SOURCE_BADGES.getOrDefault(listing.sourceId(), capitalize(listing.sourceId()))
        : "";
    sb.append("🕐 ").append(time).append("  ·  ").append(badge);
  }

  private String formatPrice(BigDecimal price, String currency) {
    String formatted = formatNumber(price);
    if ("USD".equals(currency)) {
      return "<b>$" + formatted + "</b>";
    }
    if ("BYN".equals(currency)) {
      return "<b>" + formatted + " BYN</b>";
    }
    return "<b>" + formatted + " " + (currency != null ? currency : "") + "</b>";
  }

  private String formatLocation(String district, String city) {
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

  private String formatArea(BigDecimal area) {
    return area.stripTrailingZeros().toPlainString() + " м²";
  }

  private String formatNumber(BigDecimal number) {
    BigDecimal stripped = number.stripTrailingZeros();
    if (stripped.scale() <= 0) {
      return String.format(Locale.US, "%,d", stripped.longValueExact())
          .replace(",", " ");
    }
    return stripped.toPlainString();
  }

  private String escapeHtml(String text) {
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }

  private String capitalize(String s) {
    if (s.isEmpty()) {
      return s;
    }
    return Character.toUpperCase(s.charAt(0)) + s.substring(1);
  }
}
