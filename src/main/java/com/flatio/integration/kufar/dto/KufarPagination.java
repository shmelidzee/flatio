package com.flatio.integration.kufar.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Pagination metadata from Kufar search response. Kufar uses cursor-based pagination.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KufarPagination(List<KufarPageLink> pages) {}
