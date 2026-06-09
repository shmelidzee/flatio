package com.flatio.integration.onliner.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Top-level response from the Onliner apartments search API.
 */
public record OnlinerSearchResponse(
    @JsonProperty("apartments") List<OnlinerApartment> apartments,
    @JsonProperty("total") Integer total,
    @JsonProperty("page") OnlinerPage page
) {}
