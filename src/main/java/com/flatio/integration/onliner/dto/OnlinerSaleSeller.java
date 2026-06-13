package com.flatio.integration.onliner.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Seller information returned by the Onliner purchase API.
 *
 * <p>{@code type} is either {@code "owner"} (private seller) or {@code "agent"} (realtor).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OnlinerSaleSeller(
    @JsonProperty("type") String type
) {}
