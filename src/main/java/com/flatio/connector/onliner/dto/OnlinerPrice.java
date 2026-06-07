package com.flatio.connector.onliner.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Price data returned by the Onliner API. Amount is a string to preserve precision.
 */
public record OnlinerPrice(
    @JsonProperty("amount") String amount,
    @JsonProperty("currency") String currency
) {}
