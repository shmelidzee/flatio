package com.flatio.integration.onliner.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A price value in a specific currency as returned in the {@code converted} map
 * of the Onliner API price object.
 *
 * <p>Amount is represented as a {@link String} to preserve decimal precision
 * without floating-point rounding.
 */
public record OnlinerConvertedPrice(
    @JsonProperty("amount") String amount,
    @JsonProperty("currency") String currency
) {}
