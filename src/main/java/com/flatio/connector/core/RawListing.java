package com.flatio.connector.core;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Raw listing data fetched from an external source.
 *
 * <p>Carries unvalidated, unprocessed data from a connector to the service layer.
 * Field values reflect what the source provides; optional fields may be null.
 * The service is responsible for validation, mapping, and persistence.
 */
public record RawListing(
    String externalId,
    String title,
    String description,
    String dealType,
    String propertyType,
    BigDecimal price,
    String currency,
    Integer rooms,
    Integer floorNumber,
    Integer floorsTotal,
    BigDecimal areaTotalM2,
    String address,
    BigDecimal latitude,
    BigDecimal longitude,
    String city,
    String sourceUrl,
    Instant publishedAt,
    List<String> photoUrls
) {}
