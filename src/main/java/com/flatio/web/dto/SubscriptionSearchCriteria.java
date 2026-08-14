package com.flatio.web.dto;

import com.flatio.domain.listing.DealType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

/**
 * Search filter stored on a subscription, matching the shape of {@link ListingSearchCriteria}
 * minus the {@code status} field which does not apply to a saved filter.
 *
 * <p>All fields are optional. Persisted as a JSON document on {@code Subscription.searchCriteria}.
 */
@Schema(description = "Search filter that a subscription matches new and changed listings against")
public record SubscriptionSearchCriteria(
    @Schema(description = "Deal type filter: RENT, SELL, or RENT_DAILY", example = "RENT_DAILY")
    DealType dealType,

    @Schema(description = "Property type filter", example = "APARTMENT")
    String propertyType,

    @Schema(description = "Source platform code filter", example = "onliner")
    String sourceId,

    @Schema(description = "City name filter (case-insensitive)", example = "Минск")
    String city,

    @Schema(description = "City ID filter (from cities reference table)", example = "1")
    Long cityId,

    @Schema(description = "Minimum price (inclusive)", example = "500")
    BigDecimal priceMin,

    @Schema(description = "Maximum price (inclusive)", example = "1500")
    BigDecimal priceMax,

    @Schema(description = "Number of rooms filter", example = "2")
    Integer rooms,

    @Schema(description = "Full-text search query against title, description and address",
        example = "двухкомнатная квартира Минск")
    String query,

    @Schema(description = "When true, only listings from direct owners match", example = "true")
    Boolean ownerOnly
) {}
