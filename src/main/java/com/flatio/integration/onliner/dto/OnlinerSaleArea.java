package com.flatio.integration.onliner.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Area breakdown for a sale apartment returned by the Onliner purchase API.
 *
 * <p>All dimensions are in square meters. Fields may be null when the seller
 * did not provide detailed area breakdown.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OnlinerSaleArea(
    @JsonProperty("total") Double total,
    @JsonProperty("living") Double living,
    @JsonProperty("kitchen") Double kitchen
) {}
