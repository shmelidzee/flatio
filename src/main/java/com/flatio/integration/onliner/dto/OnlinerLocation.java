package com.flatio.integration.onliner.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

/**
 * Geographic location data returned by the Onliner API.
 *
 * <p>Unknown fields (e.g. {@code user_address}) are silently ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OnlinerLocation(
    @JsonProperty("address") String address,
    @JsonProperty("latitude") BigDecimal latitude,
    @JsonProperty("longitude") BigDecimal longitude
) {}
