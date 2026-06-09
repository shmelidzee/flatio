package com.flatio.integration.onliner.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;

/**
 * Single apartment listing returned by the Onliner API.
 *
 * <p>Fields match the real Onliner REST API structure. Snake-case JSON properties
 * are mapped to camelCase Java fields via {@link JsonProperty}. Unknown fields
 * (e.g. {@code up_available_in}) are silently ignored — the API may return
 * additional fields that are not relevant to ingestion.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OnlinerApartment(
    @JsonProperty("id") Long id,
    @JsonProperty("url") String url,
    @JsonProperty("rent_type") String rentType,
    @JsonProperty("photo") String photo,
    @JsonProperty("price") OnlinerPrice price,
    @JsonProperty("location") OnlinerLocation location,
    @JsonProperty("contact") OnlinerContact contact,
    @JsonProperty("created_at") OffsetDateTime createdAt,
    @JsonProperty("last_time_up") OffsetDateTime lastTimeUp
) {}
