package com.flatio.connector.onliner.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Pagination metadata returned by the Onliner API.
 */
public record OnlinerPage(
    @JsonProperty("limit") Integer limit,
    @JsonProperty("items") Integer items,
    @JsonProperty("current") Integer current,
    @JsonProperty("last") Integer last
) {}
