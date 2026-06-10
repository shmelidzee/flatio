package com.flatio.telegram.formatter;

import com.flatio.web.dto.ListingSummaryResponse;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

@ExtendWith(MockitoExtension.class)
class ListingFormatterTest {

  /**
   * Non-breaking space (U+00A0) used by formatNumber as the thousands separator.
   * The formatter replaces the comma from "%,d" with this character.
   */
  private static final char NBSP = ' ';

  @InjectMocks
  private ListingFormatter listingFormatter;

  // -------------------------------------------------------------------------
  // buildCaption — full caption with all fields
  // -------------------------------------------------------------------------

  @Test
  void should_format_caption_with_all_zones_when_all_fields_present() {
    // Given
    var listing = buildListing(
        1L,
        "2-комнатная квартира, Минск",
        BigDecimal.valueOf(50000),
        "BYN",
        "Советский район",
        "Минск",
        BigDecimal.valueOf(52.5),
        "realt",
        Instant.parse("2026-05-01T10:30:00Z"),
        null,
        "https://realt.by/listing/1"
    );

    // When
    var caption = listingFormatter.buildCaption(listing);

    // Then
    assertThat(caption).contains("2-комнатная квартира, Минск");
    assertThat(caption).contains("50" + NBSP + "000 BYN");
    assertThat(caption).contains("📍"); // 📍
    assertThat(caption).contains("Советский район, Минск");
    assertThat(caption).contains("м²");
    assertThat(caption).contains("🕐"); // 🕐
  }

  // -------------------------------------------------------------------------
  // buildCaption — price formatting
  // -------------------------------------------------------------------------

  @Test
  void should_format_usd_price_with_dollar_sign() {
    // Given
    var listing = buildListing(
        2L, "Квартира", BigDecimal.valueOf(511), "USD",
        null, null, null, "kufar", null, null, "https://kufar.by/1"
    );

    // When
    var caption = listingFormatter.buildCaption(listing);

    // Then
    assertThat(caption).contains("$511");
  }

  @Test
  void should_format_byn_price_with_byn_suffix() {
    // Given
    var listing = buildListing(
        3L, "Квартира", BigDecimal.valueOf(1410), "BYN",
        null, null, null, "kufar", null, null, "https://kufar.by/2"
    );

    // When
    var caption = listingFormatter.buildCaption(listing);

    // Then — thousands separator is non-breaking space (U+00A0)
    assertThat(caption).contains("1" + NBSP + "410 BYN");
  }

  @Test
  void should_format_other_currency() {
    // Given
    var listing = buildListing(
        4L, "Квартира", BigDecimal.valueOf(500), "EUR",
        null, null, null, "onliner", null, null, "https://onliner.by/1"
    );

    // When
    var caption = listingFormatter.buildCaption(listing);

    // Then
    assertThat(caption).contains("500 EUR");
  }

  // -------------------------------------------------------------------------
  // buildCaption — area
  // -------------------------------------------------------------------------

  @Test
  void should_omit_area_when_null() {
    // Given
    var listing = buildListing(
        5L, "Квартира", BigDecimal.valueOf(500), "USD",
        "Советский район", "Минск", null, "realt", null, null, "https://realt.by/1"
    );

    // When
    var caption = listingFormatter.buildCaption(listing);

    // Then
    assertThat(caption).doesNotContain("м²");
  }

  // -------------------------------------------------------------------------
  // buildCaption — location zone
  // -------------------------------------------------------------------------

  @Test
  void should_omit_location_zone_when_city_and_district_null() {
    // Given
    var listing = buildListing(
        6L, "Квартира", BigDecimal.valueOf(500), "USD",
        null, null, null, "realt", null, null, "https://realt.by/2"
    );

    // When
    var caption = listingFormatter.buildCaption(listing);

    // Then
    assertThat(caption).doesNotContain("📍"); // 📍
  }

  @Test
  void should_use_district_and_city_when_both_present() {
    // Given
    var listing = buildListing(
        7L, "Квартира", BigDecimal.valueOf(500), "USD",
        "Советский район", "Минск", null, "realt", null, null, "https://realt.by/3"
    );

    // When
    var caption = listingFormatter.buildCaption(listing);

    // Then
    assertThat(caption).contains("Советский район, Минск");
  }

  @Test
  void should_use_only_city_when_district_null() {
    // Given
    var listing = buildListing(
        8L, "Квартира", BigDecimal.valueOf(500), "USD",
        null, "Минск", null, "realt", null, null, "https://realt.by/4"
    );

    // When
    var caption = listingFormatter.buildCaption(listing);

    // Then
    assertThat(caption).contains("📍"); // 📍
    assertThat(caption).contains("Минск");
    assertThat(caption).doesNotContain(", Минск");
  }

  @Test
  void should_use_only_district_when_city_null() {
    // Given
    var listing = buildListing(
        9L, "Квартира", BigDecimal.valueOf(500), "USD",
        "Советский район", null, null, "realt", null, null, "https://realt.by/5"
    );

    // When
    var caption = listingFormatter.buildCaption(listing);

    // Then
    assertThat(caption).contains("📍"); // 📍
    assertThat(caption).contains("Советский район");
  }

  // -------------------------------------------------------------------------
  // buildCaption — source badge
  // -------------------------------------------------------------------------

  @Test
  void should_map_kufar_source_badge() {
    // Given
    var listing = buildListing(
        10L, "Квартира", BigDecimal.valueOf(500), "USD",
        null, null, null, "kufar", null, null, "https://kufar.by/1"
    );

    // When
    var caption = listingFormatter.buildCaption(listing);

    // Then
    assertThat(caption).contains("Kufar");
  }

  @Test
  void should_map_onliner_source_badge() {
    // Given
    var listing = buildListing(
        11L, "Квартира", BigDecimal.valueOf(500), "USD",
        null, null, null, "onliner", null, null, "https://onliner.by/1"
    );

    // When
    var caption = listingFormatter.buildCaption(listing);

    // Then
    assertThat(caption).contains("Onliner");
  }

  @Test
  void should_map_realt_source_badge() {
    // Given
    var listing = buildListing(
        12L, "Квартира", BigDecimal.valueOf(500), "USD",
        null, null, null, "realt", null, null, "https://realt.by/1"
    );

    // When
    var caption = listingFormatter.buildCaption(listing);

    // Then
    assertThat(caption).contains("Realt.by");
  }

  @Test
  void should_capitalize_unknown_source_badge() {
    // Given
    var listing = buildListing(
        13L, "Квартира", BigDecimal.valueOf(500), "USD",
        null, null, null, "newsource", null, null, "https://newsource.by/1"
    );

    // When
    var caption = listingFormatter.buildCaption(listing);

    // Then
    assertThat(caption).contains("Newsource");
  }

  // -------------------------------------------------------------------------
  // buildCaption — truncation
  // -------------------------------------------------------------------------

  @Test
  void should_truncate_zone2_when_caption_exceeds_limit() {
    // Given — title of 960 Cyrillic chars: caption with zone2 > 1024, without zone2 <= 1024
    String longTitle = "а".repeat(960);
    var listing = buildListing(
        14L, longTitle, BigDecimal.valueOf(500), "USD",
        "Советский район", "Минск", BigDecimal.valueOf(52.5),
        "realt", Instant.parse("2026-05-01T10:30:00Z"), null, "https://realt.by/1"
    );

    // When
    var caption = listingFormatter.buildCaption(listing);

    // Then
    assertThat(caption.length()).isLessThanOrEqualTo(1024);
    assertThat(caption).doesNotContain("📍"); // 📍 — zone2 must be dropped
  }

  // -------------------------------------------------------------------------
  // buildCaption — HTML escaping
  // -------------------------------------------------------------------------

  @Test
  void should_escape_html_in_title() {
    // Given
    var listing = buildListing(
        15L, "Квартира & <дом> > гараж", BigDecimal.valueOf(500), "USD",
        null, null, null, "realt", null, null, "https://realt.by/1"
    );

    // When
    var caption = listingFormatter.buildCaption(listing);

    // Then
    assertThat(caption).contains("&amp;");
    assertThat(caption).contains("&lt;");
    assertThat(caption).contains("&gt;");
    assertThat(caption).doesNotContain(" & ");
    assertThat(caption).doesNotContain("<дом>");
  }

  // -------------------------------------------------------------------------
  // buildCaption — null publishedAt
  // -------------------------------------------------------------------------

  @Test
  void should_format_caption_without_published_at_when_null() {
    // Given
    var listing = buildListing(
        16L, "Квартира", BigDecimal.valueOf(500), "USD",
        null, null, null, "realt", null, null, "https://realt.by/1"
    );

    // When / Then — no NPE, zone3 still rendered with clock icon
    assertThatNoException().isThrownBy(() -> {
      var caption = listingFormatter.buildCaption(listing);
      assertThat(caption).contains("🕐"); // 🕐
    });
  }

  // -------------------------------------------------------------------------
  // buildKeyboard
  // -------------------------------------------------------------------------

  @Test
  void should_build_keyboard_with_source_url() {
    // Given
    var url = "https://kufar.by/listing/42";

    // When
    var keyboard = listingFormatter.buildKeyboard(url);

    // Then
    assertThat(keyboard).isNotNull();
    var rows = keyboard.getKeyboard();
    assertThat(rows).hasSize(1);
    var buttons = rows.get(0);
    assertThat(buttons).hasSize(1);
    var button = buttons.get(0);
    assertThat(button.getText()).isEqualTo("Открыть объявление →");
    assertThat(button.getUrl()).isEqualTo(url);
  }

  // -------------------------------------------------------------------------
  // helpers
  // -------------------------------------------------------------------------

  private static ListingSummaryResponse buildListing(
      Long id,
      String title,
      BigDecimal price,
      String currency,
      String district,
      String city,
      BigDecimal areaTotalM2,
      String sourceId,
      Instant publishedAt,
      String photoUrl,
      String sourceUrl
  ) {
    return new ListingSummaryResponse(
        id, title, price, currency, null,
        areaTotalM2, city, district,
        sourceId, publishedAt, photoUrl, sourceUrl
    );
  }
}
