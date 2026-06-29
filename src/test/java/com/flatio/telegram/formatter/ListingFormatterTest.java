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
  // buildCaption — zone 1: first line format (issue #175)
  // -------------------------------------------------------------------------

  @Test
  void should_format_first_line_with_rooms_and_price_in_usd_and_byn() {
    // Given
    var listing = buildListingWithRooms(
        1L,
        1,
        null,
        BigDecimal.valueOf(1524.38),
        "BYN",
        BigDecimal.valueOf(550),
        "Городецкая улица, 10",
        null,
        "onliner",
        Instant.parse("2026-05-25T18:56:00Z"),
        "https://onliner.by/1"
    );

    // When
    var caption = listingFormatter.buildCaption(listing);

    // Then
    assertThat(caption).startsWith("1-комнатная за <b>$550 (1524.38 BYN)</b>");
  }

  @Test
  void should_format_first_line_with_one_room_usd_byn() {
    // Given
    var listing = buildListingWithRooms(
        2L,
        1,
        null,
        BigDecimal.valueOf(1524.38),
        "BYN",
        BigDecimal.valueOf(550),
        null,
        null,
        "onliner",
        null,
        "https://onliner.by/2"
    );

    // When
    var caption = listingFormatter.buildCaption(listing);

    // Then
    assertThat(caption).contains("1-комнатная за");
    assertThat(caption).contains("$550");
    assertThat(caption).contains("1524.38 BYN");
  }

  @Test
  void should_format_first_line_with_two_rooms_usd_byn() {
    // Given
    var listing = buildListingWithRooms(
        3L,
        2,
        null,
        BigDecimal.valueOf(2800.00),
        "BYN",
        BigDecimal.valueOf(1000),
        null,
        null,
        "realt",
        null,
        "https://realt.by/1"
    );

    // When
    var caption = listingFormatter.buildCaption(listing);

    // Then
    assertThat(caption).contains("2-комнатная за");
    assertThat(caption).containsPattern("\\$1.000");
  }

  @Test
  void should_format_first_line_with_three_rooms_usd_byn() {
    // Given
    var listing = buildListingWithRooms(
        4L,
        3,
        null,
        BigDecimal.valueOf(4200.00),
        "BYN",
        BigDecimal.valueOf(1500),
        null,
        null,
        "kufar",
        null,
        "https://kufar.by/1"
    );

    // When
    var caption = listingFormatter.buildCaption(listing);

    // Then
    assertThat(caption).contains("3-комнатная за");
    assertThat(caption).containsPattern("\\$1.500");
  }

  @Test
  void should_place_address_on_second_line_when_district_present() {
    // Given
    var listing = buildListingWithRooms(
        5L,
        1,
        null,
        BigDecimal.valueOf(1524.38),
        "BYN",
        BigDecimal.valueOf(550),
        "Городецкая улица, 10",
        null,
        "onliner",
        null,
        "https://onliner.by/3"
    );

    // When
    var caption = listingFormatter.buildCaption(listing);

    // Then — second line is address without 📍 icon
    String[] lines = caption.split("\n");
    assertThat(lines[0]).contains("1-комнатная за");
    assertThat(lines[1]).isEqualTo("Городецкая улица, 10");
    assertThat(caption).doesNotContain("📍");
  }

  @Test
  void should_not_change_zone3_format_when_zone1_is_fixed() {
    // Given
    var listing = buildListingWithRooms(
        6L,
        1,
        null,
        BigDecimal.valueOf(1524.38),
        "BYN",
        BigDecimal.valueOf(550),
        null,
        null,
        "onliner",
        Instant.parse("2026-05-25T18:56:00Z"),
        "https://onliner.by/4"
    );

    // When
    var caption = listingFormatter.buildCaption(listing);

    // Then — zone3 unchanged: clock icon, time, source badge
    assertThat(caption).contains("🕐");
    assertThat(caption).contains("Onliner");
    assertThat(caption).contains("21:56, 25.05.2026");
  }

  @Test
  void should_format_first_line_correctly_for_onliner_source() {
    // Given
    var listing = buildListingWithRooms(
        7L,
        1,
        null,
        BigDecimal.valueOf(1524.38),
        "BYN",
        BigDecimal.valueOf(550),
        null,
        null,
        "onliner",
        null,
        "https://onliner.by/5"
    );

    // When
    var caption = listingFormatter.buildCaption(listing);

    // Then
    assertThat(caption).contains("1-комнатная за <b>$550 (1524.38 BYN)</b>");
    assertThat(caption).contains("Onliner");
  }

  @Test
  void should_format_first_line_correctly_for_realt_source() {
    // Given
    var listing = buildListingWithRooms(
        8L,
        2,
        null,
        BigDecimal.valueOf(2800.00),
        "BYN",
        BigDecimal.valueOf(1000),
        null,
        null,
        "realt",
        null,
        "https://realt.by/2"
    );

    // When
    var caption = listingFormatter.buildCaption(listing);

    // Then
    assertThat(caption).contains("2-комнатная за");
    assertThat(caption).contains("Realt");
  }

  @Test
  void should_format_first_line_correctly_for_kufar_source() {
    // Given
    var listing = buildListingWithRooms(
        9L,
        3,
        null,
        BigDecimal.valueOf(4200.00),
        "BYN",
        BigDecimal.valueOf(1500),
        null,
        null,
        "kufar",
        null,
        "https://kufar.by/2"
    );

    // When
    var caption = listingFormatter.buildCaption(listing);

    // Then
    assertThat(caption).contains("3-комнатная за");
    assertThat(caption).contains("Kufar");
  }

  @Test
  void should_omit_room_prefix_when_rooms_null() {
    // Given — no rooms count, no propertyType
    var listing = buildListingWithRooms(
        10L,
        null,
        null,
        BigDecimal.valueOf(50000),
        "BYN",
        null,
        null,
        null,
        "realt",
        null,
        "https://realt.by/3"
    );

    // When
    var caption = listingFormatter.buildCaption(listing);

    // Then — starts with price, no "за" prefix
    assertThat(caption).doesNotContain("-комнатная");
    assertThat(caption).doesNotContain(" за ");
    assertThat(caption).contains("BYN");
  }

  @Test
  void should_use_komnat_prefix_for_room_property_type() {
    // Given
    var listing = buildListingWithRooms(
        11L,
        null,
        "ROOM",
        BigDecimal.valueOf(800),
        "BYN",
        null,
        null,
        null,
        "realt",
        null,
        "https://realt.by/4"
    );

    // When
    var caption = listingFormatter.buildCaption(listing);

    // Then
    assertThat(caption).contains("Комната за");
  }

  @Test
  void should_display_negotiable_label_when_isNegotiable_is_true() {
    // Given — kufar listing with price not disclosed by seller
    var listing = new ListingSummaryResponse(
        60L, null, BigDecimal.ZERO, "BYN",
        null, null, 3, null,
        null, "Минск", null,
        null, "kufar", Instant.parse("2026-06-01T10:00:00Z"), null,
        "https://re.kufar.by/60", true
    );

    // When
    var caption = listingFormatter.buildCaption(listing);

    // Then — price shown as "Договорная" in bold, not as "0 BYN"
    assertThat(caption).contains("Договорная");
    assertThat(caption).doesNotContain("BYN");
    assertThat(caption).doesNotContain("0 B");
  }

  // -------------------------------------------------------------------------
  // buildCaption — full caption with all fields
  // -------------------------------------------------------------------------

  @Test
  void should_format_caption_with_all_zones_when_all_fields_present() {
    // Given
    var listing = buildListing(
        12L,
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

    // Then — zone1 contains price and address, zone2 has time; area is never shown
    assertThat(caption).containsPattern("50.000 BYN");
    assertThat(caption).contains("Советский район, Минск");
    assertThat(caption).doesNotContain("📍");
    assertThat(caption).doesNotContain("м²");
    assertThat(caption).contains("🕐");
  }

  // -------------------------------------------------------------------------
  // buildCaption — price formatting
  // -------------------------------------------------------------------------

  @Test
  void should_format_usd_price_with_dollar_sign() {
    // Given
    var listing = buildListing(
        13L,
        BigDecimal.valueOf(511),
        "USD",
        null,
        null,
        null,
        "kufar",
        null,
        null,
        "https://kufar.by/1"
    );

    // When
    var caption = listingFormatter.buildCaption(listing);

    // Then
    assertThat(caption).contains("$511");
  }

  @Test
  void should_format_usd_price_with_byn_equivalent_when_price_byn_is_not_null() {
    // Given — Realt listing: price stored in USD, priceByn computed from NBRB rate, priceUsd is null
    var listing = new ListingSummaryResponse(
        40L, null, BigDecimal.valueOf(650), "USD",
        null, BigDecimal.valueOf(1_835), null, null,
        null, null, null, null,
        "realt", null, null,
        "https://realt.by/40", null
    );

    // When
    var caption = listingFormatter.buildCaption(listing);

    // Then — format: "$650 (1 835 BYN)" with thousands separator in BYN part
    assertThat(caption).contains("$650");
    assertThat(caption).containsPattern("1.835 BYN");
  }

  @Test
  void should_format_usd_price_without_byn_when_price_byn_is_null() {
    // Given — USD listing without BYN equivalent (rate was unavailable at parse time)
    var listing = new ListingSummaryResponse(
        41L, null, BigDecimal.valueOf(650), "USD",
        null, null, null, null,
        null, null, null, null,
        "realt", null, null,
        "https://realt.by/41", null
    );

    // When
    var caption = listingFormatter.buildCaption(listing);

    // Then — only USD shown, no BYN equivalent in brackets
    assertThat(caption).contains("$650");
    assertThat(caption).doesNotContain("BYN");
  }

  @Test
  void should_format_byn_price_with_byn_suffix() {
    // Given
    var listing = buildListing(
        14L,
        BigDecimal.valueOf(1410),
        "BYN",
        null,
        null,
        null,
        "kufar",
        null,
        null,
        "https://kufar.by/2"
    );

    // When
    var caption = listingFormatter.buildCaption(listing);

    // Then — thousands separator is non-breaking space (U+00A0)
    assertThat(caption).containsPattern("1.410 BYN");
  }

  @Test
  void should_format_other_currency() {
    // Given
    var listing = buildListing(
        15L,
        BigDecimal.valueOf(500),
        "EUR",
        null,
        null,
        null,
        "onliner",
        null,
        null,
        "https://onliner.by/1"
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
        16L,
        BigDecimal.valueOf(500),
        "USD",
        "Советский район",
        "Минск",
        null,
        "realt",
        null,
        null,
        "https://realt.by/1"
    );

    // When
    var caption = listingFormatter.buildCaption(listing);

    // Then
    assertThat(caption).doesNotContain("м²");
  }

  // -------------------------------------------------------------------------
  // buildCaption — location in zone 1 (no 📍 icon, issue #175)
  // -------------------------------------------------------------------------

  @Test
  void should_omit_address_line_when_city_and_district_null() {
    // Given
    var listing = buildListing(
        17L,
        BigDecimal.valueOf(500),
        "USD",
        null,
        null,
        null,
        "realt",
        null,
        null,
        "https://realt.by/2"
    );

    // When
    var caption = listingFormatter.buildCaption(listing);

    // Then — no location icon, no address line
    assertThat(caption).doesNotContain("📍");
  }

  @Test
  void should_use_district_and_city_when_both_present() {
    // Given
    var listing = buildListing(
        18L,
        BigDecimal.valueOf(500),
        "USD",
        "Советский район",
        "Минск",
        null,
        "realt",
        null,
        null,
        "https://realt.by/3"
    );

    // When
    var caption = listingFormatter.buildCaption(listing);

    // Then — address on second line without 📍 icon
    assertThat(caption).contains("Советский район, Минск");
    assertThat(caption).doesNotContain("📍");
  }

  @Test
  void should_use_only_city_when_district_null() {
    // Given
    var listing = buildListing(
        19L,
        BigDecimal.valueOf(500),
        "USD",
        null,
        "Минск",
        null,
        "realt",
        null,
        null,
        "https://realt.by/4"
    );

    // When
    var caption = listingFormatter.buildCaption(listing);

    // Then — city on second line without 📍 icon
    assertThat(caption).doesNotContain("📍");
    assertThat(caption).contains("Минск");
    assertThat(caption).doesNotContain(", Минск");
  }

  @Test
  void should_use_only_district_when_city_null() {
    // Given
    var listing = buildListing(
        20L,
        BigDecimal.valueOf(500),
        "USD",
        "Советский район",
        null,
        null,
        "realt",
        null,
        null,
        "https://realt.by/5"
    );

    // When
    var caption = listingFormatter.buildCaption(listing);

    // Then — district on second line without 📍 icon
    assertThat(caption).doesNotContain("📍");
    assertThat(caption).contains("Советский район");
  }

  @Test
  void should_use_address_field_when_present() {
    // Given — address field set, district and city also present but should be ignored
    var listing = new ListingSummaryResponse(
        30L, null, BigDecimal.valueOf(500), "USD", null, null, 2, null, null,
        "Минск", "Советский район", "ул. Пушкина, 5", "realt", null, null,
        "https://realt.by/30", null
    );

    // When
    var caption = listingFormatter.buildCaption(listing);

    // Then — address field takes priority; district and city are not shown
    assertThat(caption).contains("ул. Пушкина, 5");
    assertThat(caption).doesNotContain("Советский район");
    assertThat(caption).doesNotContain("Минск");
  }

  @Test
  void should_show_address_when_district_and_city_null() {
    // Given — only address field, no district or city
    var listing = new ListingSummaryResponse(
        31L, null, BigDecimal.valueOf(500), "USD", null, null, 2, null, null,
        null, null, "ул. Ленина, 10", "realt", null, null,
        "https://realt.by/31", null
    );

    // When
    var caption = listingFormatter.buildCaption(listing);

    // Then — address shown on second line
    String[] lines = caption.split("\n");
    assertThat(lines[1]).isEqualTo("ул. Ленина, 10");
  }

  @Test
  void should_omit_address_line_when_address_district_city_all_null() {
    // Given — all location fields null
    var listing = new ListingSummaryResponse(
        32L, null, BigDecimal.valueOf(500), "USD", null, null, 2, null, null,
        null, null, null, "realt", null, null,
        "https://realt.by/32", null
    );

    // When
    var caption = listingFormatter.buildCaption(listing);

    // Then — no location line; split("\n") yields [price, "", zone3] = 3 lines
    assertThat(caption.split("\n")).hasSize(3);
    assertThat(caption).doesNotContain("📍");
  }

  @Test
  void should_fallback_to_district_and_city_when_address_null() {
    // Given — address null, district and city present
    var listing = new ListingSummaryResponse(
        33L, null, BigDecimal.valueOf(500), "USD", null, null, 2, null, null,
        "Минск", "Советский район", null, "realt", null, null,
        "https://realt.by/33", null
    );

    // When
    var caption = listingFormatter.buildCaption(listing);

    // Then — falls back to "district, city"
    assertThat(caption).contains("Советский район, Минск");
  }

  // -------------------------------------------------------------------------
  // buildCaption — source badge
  // -------------------------------------------------------------------------

  @Test
  void should_map_kufar_source_badge() {
    // Given
    var listing = buildListing(
        21L,
        BigDecimal.valueOf(500),
        "USD",
        null,
        null,
        null,
        "kufar",
        null,
        null,
        "https://kufar.by/1"
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
        22L,
        BigDecimal.valueOf(500),
        "USD",
        null,
        null,
        null,
        "onliner",
        null,
        null,
        "https://onliner.by/1"
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
        23L,
        BigDecimal.valueOf(500),
        "USD",
        null,
        null,
        null,
        "realt",
        null,
        null,
        "https://realt.by/1"
    );

    // When
    var caption = listingFormatter.buildCaption(listing);

    // Then
    assertThat(caption).contains("Realt");
  }

  @Test
  void should_capitalize_unknown_source_badge() {
    // Given
    var listing = buildListing(
        24L,
        BigDecimal.valueOf(500),
        "USD",
        null,
        null,
        null,
        "newsource",
        null,
        null,
        "https://newsource.by/1"
    );

    // When
    var caption = listingFormatter.buildCaption(listing);

    // Then
    assertThat(caption).contains("Newsource");
  }

  @Test
  void should_map_realt_house_sale_source_id_to_realt_badge() {
    // Given — uppercase source ID from RealtHouseSaleConnector
    var listing = buildListing(
        50L, BigDecimal.valueOf(200_000), "USD", null, null, null,
        "REALT_HOUSE_SALE", null, null, "https://realt.by/1"
    );

    // When
    var caption = listingFormatter.buildCaption(listing);

    // Then
    assertThat(caption).contains("Realt");
  }

  @Test
  void should_map_realt_room_rent_source_id_to_realt_badge() {
    // Given — uppercase source ID from RealtRoomConnector
    var listing = buildListing(
        51L, BigDecimal.valueOf(500), "USD", null, null, null,
        "REALT_ROOM", null, null, "https://realt.by/2"
    );

    // When
    var caption = listingFormatter.buildCaption(listing);

    // Then
    assertThat(caption).contains("Realt");
  }

  @Test
  void should_map_realt_room_sale_source_id_to_realt_badge() {
    // Given — uppercase source ID from RealtRoomSaleConnector
    var listing = buildListing(
        52L, BigDecimal.valueOf(50_000), "USD", null, null, null,
        "REALT_ROOM_SALE", null, null, "https://realt.by/3"
    );

    // When
    var caption = listingFormatter.buildCaption(listing);

    // Then
    assertThat(caption).contains("Realt");
  }

  @Test
  void should_map_realt_sale_source_id_to_realt_badge() {
    // Given — uppercase source ID from RealtSaleConnector
    var listing = buildListing(
        53L, BigDecimal.valueOf(80_000), "USD", null, null, null,
        "REALT_SALE", null, null, "https://realt.by/4"
    );

    // When
    var caption = listingFormatter.buildCaption(listing);

    // Then
    assertThat(caption).contains("Realt");
  }

  @Test
  void should_map_onliner_sale_source_id_to_onliner_badge() {
    // Given — uppercase source ID from OnlinerSaleConnector
    var listing = buildListing(
        54L, BigDecimal.valueOf(120_000), "USD", null, null, null,
        "ONLINER_SALE", null, null, "https://onliner.by/1"
    );

    // When
    var caption = listingFormatter.buildCaption(listing);

    // Then
    assertThat(caption).contains("Onliner");
  }

  @Test
  void should_map_uppercase_realt_base_source_id_to_realt_badge() {
    // Given — uppercase base REALT source ID
    var listing = buildListing(
        55L, BigDecimal.valueOf(700), "USD", null, null, null,
        "REALT", null, null, "https://realt.by/5"
    );

    // When
    var caption = listingFormatter.buildCaption(listing);

    // Then
    assertThat(caption).contains("Realt");
  }

  @Test
  void should_map_uppercase_onliner_base_source_id_to_onliner_badge() {
    // Given — uppercase base ONLINER source ID
    var listing = buildListing(
        56L, BigDecimal.valueOf(800), "BYN", null, null, null,
        "ONLINER", null, null, "https://onliner.by/2"
    );

    // When
    var caption = listingFormatter.buildCaption(listing);

    // Then
    assertThat(caption).contains("Onliner");
  }

  @Test
  void should_map_uppercase_kufar_source_id_to_kufar_badge() {
    // Given — uppercase KUFAR source ID
    var listing = buildListing(
        57L, BigDecimal.valueOf(600), "BYN", null, null, null,
        "KUFAR", null, null, "https://kufar.by/3"
    );

    // When
    var caption = listingFormatter.buildCaption(listing);

    // Then
    assertThat(caption).contains("Kufar");
  }

  // -------------------------------------------------------------------------
  // buildCaption — truncation
  // -------------------------------------------------------------------------

  @Test
  void should_hard_clamp_caption_when_it_exceeds_limit() {
    // Given — address of 1010 chars forces caption over the 1024-char limit
    String longAddress = "а".repeat(1010);
    var listing = new ListingSummaryResponse(
        25L,
        null,
        BigDecimal.valueOf(500),
        "USD",
        null,
        null,
        null,
        null,
        null,
        null,
        longAddress,
        null,
        "realt",
        Instant.parse("2026-05-01T10:30:00Z"),
        null,
        "https://realt.by/1",
        null
    );

    // When
    var caption = listingFormatter.buildCaption(listing);

    // Then — caption is hard-clamped to 1024 chars and ends with ellipsis
    assertThat(caption.length()).isLessThanOrEqualTo(1024);
    assertThat(caption).endsWith("…");
  }

  // -------------------------------------------------------------------------
  // buildCaption — HTML escaping
  // -------------------------------------------------------------------------

  @Test
  void should_escape_html_in_address() {
    // Given
    var listing = buildListing(
        26L,
        BigDecimal.valueOf(500),
        "USD",
        "Улица & <проспект> > дорога",
        null,
        null,
        "realt",
        null,
        null,
        "https://realt.by/1"
    );

    // When
    var caption = listingFormatter.buildCaption(listing);

    // Then
    assertThat(caption).contains("&amp;");
    assertThat(caption).contains("&lt;");
    assertThat(caption).contains("&gt;");
    assertThat(caption).doesNotContain(" & ");
    assertThat(caption).doesNotContain("<проспект>");
  }

  // -------------------------------------------------------------------------
  // buildCaption — null publishedAt
  // -------------------------------------------------------------------------

  @Test
  void should_format_caption_without_published_at_when_null() {
    // Given
    var listing = buildListing(
        27L,
        BigDecimal.valueOf(500),
        "USD",
        null,
        null,
        null,
        "realt",
        null,
        null,
        "https://realt.by/1"
    );

    // When / Then — no NPE, zone3 still rendered with clock icon
    assertThatNoException().isThrownBy(() -> {
      var caption = listingFormatter.buildCaption(listing);
      assertThat(caption).contains("🕐");
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

  /**
   * Builds a listing with rooms, propertyType, and priceUsd fields for zone-1 format tests.
   */
  private static ListingSummaryResponse buildListingWithRooms(
      Long id,
      Integer rooms,
      String propertyType,
      BigDecimal price,
      String currency,
      BigDecimal priceUsd,
      String district,
      String city,
      String sourceId,
      Instant publishedAt,
      String sourceUrl
  ) {
    return new ListingSummaryResponse(
        id, null, price, currency,
        priceUsd, null, rooms, propertyType,
        null, city, district,
        null, sourceId, publishedAt, null, sourceUrl, null
    );
  }

  /**
   * Builds a listing without rooms/propertyType/priceUsd for general formatting tests.
   */
  private static ListingSummaryResponse buildListing(
      Long id,
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
        id, null, price, currency,
        null, null, null, null,
        areaTotalM2, city, district,
        null, sourceId, publishedAt, photoUrl, sourceUrl, null
    );
  }
}
