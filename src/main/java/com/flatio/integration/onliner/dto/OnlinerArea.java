package com.flatio.integration.onliner.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

/**
 * Apartment area data returned by the Onliner API.
 */
public record OnlinerArea(
    @JsonProperty("total") BigDecimal total,
    @JsonProperty("living") BigDecimal living,
    @JsonProperty("kitchen") BigDecimal kitchen
) {}
