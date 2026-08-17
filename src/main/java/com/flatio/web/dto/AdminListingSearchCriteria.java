package com.flatio.web.dto;

import com.flatio.domain.listing.DealType;
import com.flatio.domain.listing.ListingStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

/**
 * Search criteria for {@code GET /api/v1/admin/listings}.
 *
 * <p>Unlike the public listing search, {@code status} is not defaulted to
 * {@link ListingStatus#ACTIVE} — omitting it returns listings in any status.
 * Bound from HTTP query parameters via {@code @ModelAttribute}.
 */
@Schema(description = "Filter parameters for the admin listing search")
public record AdminListingSearchCriteria(
    @Schema(description = "Deal type filter: RENT, SELL, or RENT_DAILY", example = "RENT_DAILY")
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

    @Schema(description = "Minimum total area in square meters (inclusive)", example = "30")
    BigDecimal areaMin,

    @Schema(description = "Maximum total area in square meters (inclusive)", example = "80")
    BigDecimal areaMax,

    @Schema(
        description = "Case-insensitive substring match against title, description and address. "
            + "Unlike the public search's full-text query, this is a plain substring match — moderation "
            + "tooling favours predictable matches over ranked relevance.",
        example = "Минск"
    )
    String query,

    @Schema(description = "Listing status filter; omit to search across all statuses", example = "INACTIVE")
    ListingStatus status,

    @Schema(
        description = "When true, only returns listings that share their deduplication hash with "
            + "another listing from a different source — i.e. listings that are part of a duplicate group",
        example = "true"
    )
    Boolean duplicatesOnly
) {}
