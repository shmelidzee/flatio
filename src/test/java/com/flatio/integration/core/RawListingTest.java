package com.flatio.integration.core;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RawListingTest {

  // -------------------------------------------------------------------------
  // Construction and field access
  // -------------------------------------------------------------------------

  @Test
  void should_create_raw_listing_with_all_required_fields() {
    // Given
    var publishedAt = Instant.parse("2026-06-01T10:00:00Z");
    var photos = List.of("https://example.com/photo1.jpg", "https://example.com/photo2.jpg");

    // When
    var listing = new RawListing(
        "ext-001",
        "2-комнатная квартира в Минске",
        "Просторная квартира с хорошим ремонтом",
        "RENT",
        "APARTMENT",
        BigDecimal.valueOf(500),
        "BYN",
        null,
        null,
        2,
        3,
        9,
        BigDecimal.valueOf(55.5),
        "Минск, ул. Немига, 5",
        BigDecimal.valueOf(53.9006),
        BigDecimal.valueOf(27.5590),
        "Минск",
        "https://onliner.by/listings/ext-001",
        publishedAt,
        photos,
        null,
        null,
        null
    );

    // Then
    assertThat(listing.externalId()).isEqualTo("ext-001");
    assertThat(listing.title()).isEqualTo("2-комнатная квартира в Минске");
    assertThat(listing.description()).isEqualTo("Просторная квартира с хорошим ремонтом");
    assertThat(listing.dealType()).isEqualTo("RENT");
    assertThat(listing.propertyType()).isEqualTo("APARTMENT");
    assertThat(listing.price()).isEqualByComparingTo(BigDecimal.valueOf(500));
    assertThat(listing.currency()).isEqualTo("BYN");
    assertThat(listing.rooms()).isEqualTo(2);
    assertThat(listing.floorNumber()).isEqualTo(3);
    assertThat(listing.floorsTotal()).isEqualTo(9);
    assertThat(listing.areaTotalM2()).isEqualByComparingTo(BigDecimal.valueOf(55.5));
    assertThat(listing.address()).isEqualTo("Минск, ул. Немига, 5");
    assertThat(listing.city()).isEqualTo("Минск");
    assertThat(listing.sourceUrl()).isEqualTo("https://onliner.by/listings/ext-001");
    assertThat(listing.publishedAt()).isEqualTo(publishedAt);
    assertThat(listing.photoUrls()).hasSize(2);
  }

  @Test
  void should_create_raw_listing_with_nullable_fields_as_null() {
    // Given — minimal listing with only required fields; optional fields are null
    var listing = new RawListing(
        "ext-002",
        "Квартира без описания",
        null,    // description
        "SELL",
        null,    // propertyType
        BigDecimal.valueOf(80_000),
        "USD",
        null,    // priceUsd
        null,    // priceByn
        null,    // rooms
        null,    // floorNumber
        null,    // floorsTotal
        null,    // areaTotalM2
        null,    // address
        null,    // latitude
        null,    // longitude
        null,    // city
        "https://realt.by/listings/ext-002",
        null,    // publishedAt
        List.of(),
        null,    // isOwner
        null,    // priceUnit
        null     // isNegotiable
    );

    // Then
    assertThat(listing.externalId()).isEqualTo("ext-002");
    assertThat(listing.description()).isNull();
    assertThat(listing.propertyType()).isNull();
    assertThat(listing.rooms()).isNull();
    assertThat(listing.floorNumber()).isNull();
    assertThat(listing.floorsTotal()).isNull();
    assertThat(listing.areaTotalM2()).isNull();
    assertThat(listing.address()).isNull();
    assertThat(listing.latitude()).isNull();
    assertThat(listing.longitude()).isNull();
    assertThat(listing.city()).isNull();
    assertThat(listing.publishedAt()).isNull();
    assertThat(listing.photoUrls()).isEmpty();
  }

  // -------------------------------------------------------------------------
  // Record equality and identity
  // -------------------------------------------------------------------------

  @Test
  void should_be_equal_when_all_fields_are_identical() {
    // Given
    var listing1 = buildMinimalListing("ext-003");
    var listing2 = buildMinimalListing("ext-003");

    // When / Then — records with identical fields are equal
    assertThat(listing1).isEqualTo(listing2);
    assertThat(listing1.hashCode()).isEqualTo(listing2.hashCode());
  }

  @Test
  void should_not_be_equal_when_external_id_differs() {
    // Given
    var listing1 = buildMinimalListing("ext-004a");
    var listing2 = buildMinimalListing("ext-004b");

    // When / Then
    assertThat(listing1).isNotEqualTo(listing2);
  }

  // -------------------------------------------------------------------------
  // ListingConnector contract (via anonymous implementation)
  // -------------------------------------------------------------------------

  @Test
  void should_allow_implementation_returning_empty_list_on_error() {
    // Given — a connector that returns empty on error (required behaviour)
    ListingConnector connector = new ListingConnector() {
      @Override
      public String getSourceId() {
        return "TEST_SOURCE";
      }

      @Override
      public String getSupportedRegionCode() {
        return "BY";
      }

      @Override
      public java.util.List<RawListing> fetch() {
        return List.of(); // graceful degradation
      }
    };

    // When
    var result = connector.fetch();

    // Then — never throws, always returns a list (may be empty)
    assertThat(result).isNotNull().isEmpty();
    assertThat(connector.getSourceId()).isEqualTo("TEST_SOURCE");
    assertThat(connector.getSupportedRegionCode()).isEqualTo("BY");
  }

  @Test
  void should_return_region_code_from_implementation_not_hardcoded() {
    // Given — implementations must inject region code, not hardcode it
    var regionCode = "UA"; // non-BY region to verify it's configurable
    ListingConnector connector = new ListingConnector() {
      @Override
      public String getSourceId() {
        return "UA_SOURCE";
      }

      @Override
      public String getSupportedRegionCode() {
        return regionCode; // injected, not hardcoded
      }

      @Override
      public java.util.List<RawListing> fetch() {
        return List.of();
      }
    };

    // When / Then
    assertThat(connector.getSupportedRegionCode()).isEqualTo("UA");
  }

  // -------------------------------------------------------------------------
  // Helper
  // -------------------------------------------------------------------------

  private RawListing buildMinimalListing(String externalId) {
    return new RawListing(
        externalId, "Квартира", null, "RENT", null,
        BigDecimal.valueOf(400), "BYN", null, null,
        null, null, null, null, null, null, null, null,
        "https://example.com/" + externalId, null, List.of(), null, null, null
    );
  }
}
