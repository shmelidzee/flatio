package com.flatio.integration.onliner.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Single apartment listing returned by the Onliner API.
 */
public record OnlinerApartment(
    @JsonProperty("id") Long id,
    @JsonProperty("url") String url,
    @JsonProperty("deal_type") String dealType,
    @JsonProperty("photo") String photo,
    @JsonProperty("price") OnlinerPrice price,
    @JsonProperty("location") OnlinerLocation location,
    @JsonProperty("area") OnlinerArea area,
    @JsonProperty("rooms_count") Integer roomsCount,
    @JsonProperty("floor") Integer floor,
    @JsonProperty("number_of_floors") Integer numberOfFloors,
    @JsonProperty("created_at") String createdAt
) {}
