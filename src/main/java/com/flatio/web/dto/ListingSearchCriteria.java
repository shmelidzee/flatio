package com.flatio.web.dto;

import com.flatio.domain.listing.DealType;
import com.flatio.domain.listing.ListingStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

/**
 * Search criteria for querying listings via {@code GET /api/v1/listings}.
 *
 * <p>All fields are optional. When {@code status} is null the service defaults to
 * {@link ListingStatus#ACTIVE}. Bound from HTTP query parameters via {@code @ModelAttribute}.
 */
@Schema(description = "Filter parameters for listing search")
public record ListingSearchCriteria(
    @Schema(description = "Deal type filter", example = "RENT")
    DealType dealType,

    @Schema(description = "Property type filter", example = "APARTMENT")
    String propertyType,

    @Schema(description = "Source platform code filter", example = "onliner")
    String sourceId,

    @Schema(description = "City name filter (case-insensitive)", example = "Минск")
    String city,

    @Schema(description = "Minimum price (inclusive)", example = "500")
    BigDecimal priceMin,

    @Schema(description = "Maximum price (inclusive)", example = "1500")
    BigDecimal priceMax,

    @Schema(description = "Number of rooms filter", example = "2")
    Integer rooms,

    @Schema(description = "Listing status filter; defaults to ACTIVE when null", example = "ACTIVE")
    ListingStatus status,

    @Schema(
        description = "Full-text search query against title, description and address. " +
            "Supports quoted phrases (\\\"двухкомнатная квартира\\\") and minus-word exclusion (-гараж).",
        example = "двухкомнатная квартира Минск"
    )
    String query
) {}
