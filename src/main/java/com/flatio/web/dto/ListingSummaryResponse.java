package com.flatio.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;

@Schema(description = "Summary view of a real estate listing for list displays")
public record ListingSummaryResponse(
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
    String sourceUrl
) {}
