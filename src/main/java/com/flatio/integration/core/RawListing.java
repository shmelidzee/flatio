package com.flatio.integration.core;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Raw listing data fetched from an external source.
 *
 * <p>Carries unvalidated, unprocessed data from a connector to the service layer.
 * Field values reflect what the source provides; optional fields may be null.
 * The service is responsible for validation, mapping, and persistence.
 *
 * @param priceUnit optional price-unit hint from the connector; may be {@code null}.
 *                  The ingestion service always derives the persisted value from {@code dealType}
 *                  ({@code RENT}→{@code PER_MONTH}, {@code RENT_DAILY}→{@code PER_DAY},
 *                  {@code SELL}→{@code null}), so this field is currently unused by
 *                  {@code OnlinerConnector}. It is kept as an extension point for future
 *                  connectors (e.g. Realt, Kufar) that may provide the unit directly.
 */
public record RawListing(
    String externalId,
    String title,
    String description,
    String dealType,
    String propertyType,
    BigDecimal price,
    String currency,
    BigDecimal priceUsd,
    BigDecimal priceByn,
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
    List<String> photoUrls,
    Boolean isOwner,
    String priceUnit,
    Boolean isNegotiable
) {}
