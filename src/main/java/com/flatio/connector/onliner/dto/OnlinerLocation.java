package com.flatio.connector.onliner.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

/**
 * Geographic location data returned by the Onliner API.
 */
public record OnlinerLocation(
    @JsonProperty("address") String address,
    @JsonProperty("latitude") BigDecimal latitude,
    @JsonProperty("longitude") BigDecimal longitude
) {}
