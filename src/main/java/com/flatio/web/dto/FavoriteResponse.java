package com.flatio.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * A favorited listing enriched with price and availability change indicators computed against
 * the listing's current state.
 */
@Schema(description = "A favorited listing with computed price and availability change indicators")
public record FavoriteResponse(
    @Schema(description = "Favorite entry ID", example = "7")
    Long id,

    @Schema(description = "Summary of the favorited listing")
    ListingSummaryResponse listing,

    @Schema(description = "Listing price at the moment it was added to favorites", example = "75000.00")
    BigDecimal priceAtAdd,

    @Schema(description = "Current listing price", example = "72000.00")
    BigDecimal currentPrice,

    @Schema(description = "currentPrice minus priceAtAdd", example = "-3000.00", nullable = true)
    BigDecimal priceDelta,

    @Schema(description = "True when the current price differs from the price at the time it was added",
        example = "true")
    boolean priceChanged,

    @Schema(description = "True when the listing has been taken off publication (status = INACTIVE)",
        example = "false")
    boolean listingInactive,

    @Schema(description = "Date and time the listing was added to favorites", example = "2026-01-15T11:00:00Z")
    Instant createdAt
) {}
