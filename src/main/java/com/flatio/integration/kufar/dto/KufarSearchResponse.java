package com.flatio.integration.kufar.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Top-level response from the Kufar search API.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KufarSearchResponse(
    List<KufarAd> ads,
    KufarPagination pagination,
    Integer total
) {}
