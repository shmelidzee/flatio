package com.flatio.integration.onliner.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Top-level response from the Onliner purchase apartments search API ({@code pk.api.onliner.by}).
 */
public record OnlinerSaleSearchResponse(
    @JsonProperty("apartments") List<OnlinerSaleApartment> apartments,
    @JsonProperty("total") Integer total,
    @JsonProperty("page") OnlinerPage page
) {}
