package com.flatio.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;

@Schema(description = "Summary view of a real estate listing for list displays")
public record ListingSummaryResponse(
    // NOTE: field order below is the canonical constructor. A compatibility constructor at the
    // bottom of this record accepts the pre-#415 argument list (without displayPrice/
    // displayCurrency) unchanged, so existing call sites do not need to be touched.
    @Schema(description = "Internal listing identifier", example = "42")
    Long id,

    @Schema(description = "Listing title", example = "2-комнатная квартира, 52 м², Минск")
    String title,

    @Schema(description = "Listed price in the stored currency", example = "75000.00")
    BigDecimal price,

    @Schema(description = "Currency code of the stored price", example = "BYN")
    String currency,

    @Schema(description = "Original price in USD when source publishes in USD; null otherwise", example = "85000.00")
    BigDecimal priceUsd,

    @Schema(description = "BYN equivalent of the price for USD-priced listings (e.g. Realt.by); null for BYN-priced sources", example = "2132.00")
    BigDecimal priceByn,

    @Schema(description = "Number of rooms", example = "2")
    Integer rooms,

    @Schema(description = "Property type code", example = "APARTMENT")
    String propertyType,

    @Schema(description = "Total area in square meters", example = "52.30")
    BigDecimal areaTotalM2,

    @Schema(description = "City name", example = "Минск")
    String city,

    @Schema(description = "District or neighbourhood", example = "Советский район")
    String district,

    @Schema(description = "Street address", example = "ул. Пушкина, 5")
    String address,

    @Schema(description = "Source platform code", example = "realt")
    String sourceId,

    @Schema(description = "Date and time when the listing was published on the source",
        example = "2026-01-15T10:30:00Z")
    Instant publishedAt,

    @Schema(description = "URL of the main listing photo, null if not available",
        example = "https://cdn.realt.by/photos/123456/main.jpg")
    String photoUrl,

    @Schema(description = "URL of the listing on the source platform, used for the open-listing button",
        example = "https://re.kufar.by/vi/minsk/snyat-kvartiru/123456")
    String sourceUrl,

    @Schema(description = "True when the seller did not specify a price; display as 'Договорная'",
        example = "false")
    Boolean isNegotiable,

    @Schema(description = "Price converted into displayCurrency (issue #415); null if the "
        + "required exchange rate is unavailable or the listing has no price (isNegotiable)",
        example = "27500.00", nullable = true)
    BigDecimal displayPrice,

    @Schema(description = "Currency displayPrice is expressed in — the caller's requested "
        + "targetCurrency, or BYN by default; the original stored price/currency above are "
        + "unaffected", example = "BYN", nullable = true)
    String displayCurrency
) {

  /**
   * Compatibility constructor matching this record's shape before issue #415 added
   * {@code displayPrice}/{@code displayCurrency} — defaults both to null so existing call sites
   * that only care about the original stored price/currency do not need to change.
   */
  public ListingSummaryResponse(
      Long id, String title, BigDecimal price, String currency, BigDecimal priceUsd, BigDecimal priceByn,
      Integer rooms, String propertyType, BigDecimal areaTotalM2, String city, String district, String address,
      String sourceId, Instant publishedAt, String photoUrl, String sourceUrl, Boolean isNegotiable
  ) {
    this(id, title, price, currency, priceUsd, priceByn, rooms, propertyType, areaTotalM2, city, district,
        address, sourceId, publishedAt, photoUrl, sourceUrl, isNegotiable, null, null);
  }
}
