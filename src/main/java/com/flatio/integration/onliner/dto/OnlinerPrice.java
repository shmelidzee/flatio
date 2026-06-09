package com.flatio.integration.onliner.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * Price data returned by the Onliner API.
 *
 * <p>Amount is a string to preserve decimal precision. The {@code converted} map
 * holds the same price expressed in additional currencies (e.g. {@code "BYN"}, {@code "USD"}),
 * keyed by ISO 4217 currency code.
 */
public record OnlinerPrice(
    @JsonProperty("amount") String amount,
    @JsonProperty("currency") String currency,
    @JsonProperty("converted") Map<String, OnlinerConvertedPrice> converted
) {}
