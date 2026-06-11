package com.flatio.service.domain;

import java.math.BigDecimal;

/**
 * Immutable snapshot of a user's saved search filter.
 *
 * <p>All fields are nullable — a null value means "no restriction on this dimension".
 * Used as the transfer type between the Telegram layer and {@code UserSavedSearchService}.
 *
 * @param regionCode   region code (e.g. "BY-MIN"), or null for any region
 * @param priceMin     minimum price inclusive, or null
 * @param priceMax     maximum price inclusive, or null
 * @param roomsMin     minimum room count inclusive, or null
 * @param roomsMax     maximum room count inclusive, or null
 * @param propertyType property type string (e.g. "APARTMENT"), or null
 * @param isOwner      true = owner-only listings; null = no restriction
 * @param keyword      free-text keyword, or null
 */
public record SearchFilter(
    String regionCode,
    BigDecimal priceMin,
    BigDecimal priceMax,
    Integer roomsMin,
    Integer roomsMax,
    String propertyType,
    Boolean isOwner,
    String keyword
) {}
