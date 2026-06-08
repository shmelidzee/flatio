package com.flatio.service;

import com.flatio.connector.core.RawListing;
import com.flatio.domain.listing.DealType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RawListingMapperTest {

  private final RawListingMapper mapper = new RawListingMapperImpl();

  // -------------------------------------------------------------------------
  // toDealType — string-to-enum conversion
  // -------------------------------------------------------------------------

  @Test
  void should_map_rent_deal_type_from_uppercase_string() {
    // When / Then
    assertThat(mapper.toDealType("RENT")).isEqualTo(DealType.RENT);
  }

  @Test
  void should_map_sell_deal_type_from_uppercase_string() {
    // When / Then
    assertThat(mapper.toDealType("SELL")).isEqualTo(DealType.SELL);
  }

  @Test
  void should_map_rent_daily_deal_type_from_lowercase_string() {
    // When / Then — Onliner sends "rent_daily", must map to RENT_DAILY
    assertThat(mapper.toDealType("rent_daily")).isEqualTo(DealType.RENT_DAILY);
  }

  @Test
  void should_map_rent_daily_deal_type_from_uppercase_string() {
    // When / Then
    assertThat(mapper.toDealType("RENT_DAILY")).isEqualTo(DealType.RENT_DAILY);
  }

  @Test
  void should_map_deal_type_from_lowercase_string() {
    // When / Then
    assertThat(mapper.toDealType("rent")).isEqualTo(DealType.RENT);
  }

  @Test
  void should_map_deal_type_from_mixed_case_string() {
    // When / Then
    assertThat(mapper.toDealType("Sell")).isEqualTo(DealType.SELL);
  }

  @Test
  void should_return_null_when_deal_type_is_null() {
    // When / Then — null input → null output, no exception; caller guards against null
    assertThat(mapper.toDealType(null)).isNull();
  }

  @Test
  void should_return_null_when_deal_type_is_unrecognised() {
    // When / Then — unknown value → null output, no exception
    assertThat(mapper.toDealType("EXCHANGE")).isNull();
    assertThat(mapper.toDealType("daily_rent")).isNull();
    assertThat(mapper.toDealType("AUCTION")).isNull();
  }

  // -------------------------------------------------------------------------
  // toEntity — field mapping
  // -------------------------------------------------------------------------

  @Test
  void should_map_direct_fields_from_raw_listing_to_entity() {
    // Given
    var raw = buildFullRawListing("ext-001", "RENT");

    // When
    var listing = mapper.toEntity(raw);

    // Then
    assertThat(listing.getExternalId()).isEqualTo("ext-001");
    assertThat(listing.getTitle()).isEqualTo("Test apartment");
    assertThat(listing.getDescription()).isEqualTo("Nice place");
    assertThat(listing.getDealType()).isEqualTo(DealType.RENT);
    assertThat(listing.getPropertyType()).isEqualTo("APARTMENT");
    assertThat(listing.getPrice()).isEqualByComparingTo(BigDecimal.valueOf(500));
    assertThat(listing.getRooms()).isEqualTo(2);
    assertThat(listing.getFloorNumber()).isEqualTo(3);
    assertThat(listing.getFloorsTotal()).isEqualTo(9);
    assertThat(listing.getAreaTotalM2()).isEqualByComparingTo(BigDecimal.valueOf(55.5));
    assertThat(listing.getAddress()).isEqualTo("Минск, Немига 5");
    assertThat(listing.getCity()).isEqualTo("Минск");
    assertThat(listing.getSourceUrl()).isEqualTo("https://onliner.by/1");
    assertThat(listing.getPublishedAt()).isEqualTo(Instant.parse("2026-06-01T10:00:00Z"));
  }

  @Test
  void should_map_rent_daily_entity_deal_type() {
    // Given
    var raw = buildFullRawListing("ext-daily", "rent_daily");

    // When
    var listing = mapper.toEntity(raw);

    // Then
    assertThat(listing.getDealType()).isEqualTo(DealType.RENT_DAILY);
  }

  @Test
  void should_leave_context_dependent_fields_null_after_mapping() {
    // Given
    var raw = buildFullRawListing("ext-002", "SELL");

    // When
    var listing = mapper.toEntity(raw);

    // Then — source, currency, country, status, dedupHash must be set by service
    assertThat(listing.getId()).isNull();
    assertThat(listing.getSource()).isNull();
    assertThat(listing.getCurrency()).isNull();
    assertThat(listing.getCountry()).isNull();
    assertThat(listing.getStatus()).isNull();
    assertThat(listing.getDedupHash()).isNull();
    assertThat(listing.getPriceUsd()).isNull();
  }

  @Test
  void should_map_nullable_raw_fields_to_null_on_entity() {
    // Given — minimal listing; all optional fields are null
    var raw = new RawListing(
        "ext-min", "Title", null, "RENT", null,
        BigDecimal.valueOf(300), "BYN",
        null, null, null, null, null, null, null, null,
        "https://example.com/ext-min", null, List.of()
    );

    // When
    var listing = mapper.toEntity(raw);

    // Then
    assertThat(listing.getDescription()).isNull();
    assertThat(listing.getPropertyType()).isNull();
    assertThat(listing.getRooms()).isNull();
    assertThat(listing.getFloorNumber()).isNull();
    assertThat(listing.getFloorsTotal()).isNull();
    assertThat(listing.getAreaTotalM2()).isNull();
    assertThat(listing.getAddress()).isNull();
    assertThat(listing.getLatitude()).isNull();
    assertThat(listing.getLongitude()).isNull();
    assertThat(listing.getCity()).isNull();
    assertThat(listing.getPublishedAt()).isNull();
  }

  @Test
  void should_set_deal_type_null_on_entity_when_deal_type_is_unrecognised() {
    // Given — unknown deal_type; ingestion service guards against null before saving
    var raw = buildFullRawListing("ext-003", "AUCTION");

    // When
    var listing = mapper.toEntity(raw);

    // Then — no exception; dealType is null; caller (ListingIngestionService) handles this
    assertThat(listing.getDealType()).isNull();
  }

  // -------------------------------------------------------------------------
  // Helper
  // -------------------------------------------------------------------------

  private RawListing buildFullRawListing(String externalId, String dealType) {
    return new RawListing(
        externalId, "Test apartment", "Nice place", dealType, "APARTMENT",
        BigDecimal.valueOf(500), "BYN",
        2, 3, 9, BigDecimal.valueOf(55.5),
        "Минск, Немига 5",
        BigDecimal.valueOf(53.9006), BigDecimal.valueOf(27.5590),
        "Минск", "https://onliner.by/1",
        Instant.parse("2026-06-01T10:00:00Z"),
        List.of("https://photo.com/1.jpg")
    );
  }
}
