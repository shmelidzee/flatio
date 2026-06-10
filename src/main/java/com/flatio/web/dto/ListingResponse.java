package com.flatio.web.dto;

import com.flatio.domain.listing.DealType;
import com.flatio.domain.listing.ListingStatus;
import com.flatio.domain.listing.PriceUnit;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Schema(description = "Full details of a real estate listing")
public record ListingResponse(
    @Schema(description = "Internal listing identifier", example = "42")
    Long id,

    @Schema(description = "External identifier from the source platform", example = "realt-123456")
    String externalId,

    @Schema(description = "Source platform code", example = "realt")
    String sourceId,

    @Schema(description = "Listing title", example = "2-комнатная квартира, 52 м², Минск")
    String title,

    @Schema(description = "Full description provided by the source", example = "Сдаётся квартира с мебелью")
    String description,

    @Schema(description = "Deal type: rent or sell", example = "SELL")
    DealType dealType,

    @Schema(description = "Price unit for rental listings: PER_MONTH or PER_DAY. Null for sales.",
        example = "PER_MONTH")
    PriceUnit priceUnit,

    @Schema(description = "Property type", example = "APARTMENT")
    String propertyType,

    @Schema(description = "Listed price", example = "75000.00")
    BigDecimal price,

    @Schema(description = "Currency code", example = "USD")
    String currency,

    @Schema(description = "Number of rooms", example = "2")
    Integer rooms,

    @Schema(description = "Floor number", example = "5")
    Integer floorNumber,

    @Schema(description = "Total floors in the building", example = "9")
    Integer floorsTotal,

    @Schema(description = "Total area in square meters", example = "52.30")
    BigDecimal areaTotalM2,

    @Schema(description = "Street address", example = "ул. Якуба Коласа, 5")
    String address,

    @Schema(description = "City name", example = "Минск")
    String city,

    @Schema(description = "District or neighbourhood", example = "Советский район")
    String district,

    @Schema(description = "Latitude coordinate", example = "53.9006")
    BigDecimal latitude,

    @Schema(description = "Longitude coordinate", example = "27.5590")
    BigDecimal longitude,

    @Schema(description = "True if posted directly by the property owner (not an agency)", example = "true")
    Boolean isOwner,

    @Schema(description = "Listing visibility status", example = "ACTIVE")
    ListingStatus status,

    @Schema(description = "URL of the listing on the source platform",
        example = "https://realt.by/sale/object/123456/")
    String sourceUrl,

    @Schema(description = "Date and time when the listing was published on the source",
        example = "2026-01-15T10:30:00Z")
    Instant publishedAt,

    @Schema(description = "Date and time when the listing was first ingested",
        example = "2026-01-15T11:00:00Z")
    Instant createdAt,

    @Schema(description = "Price change history, newest entry first")
    List<PriceHistoryEntry> priceHistory
) {}
