package com.flatio.integration.onliner.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;

/**
 * Single apartment listing returned by the Onliner purchase API ({@code pk.api.onliner.by}).
 *
 * <p>Differs from the rental API ({@code ak.api.onliner.by}) in several key ways:
 * <ul>
 *   <li>{@code number_of_rooms} is an explicit integer, not derived from {@code rent_type}.</li>
 *   <li>{@code seller.type} indicates owner vs. agent (replaces {@code contact.owner}).</li>
 *   <li>{@code area} provides a detailed breakdown of total, living and kitchen space.</li>
 *   <li>Prices are in the range of tens of thousands USD rather than hundreds BYN/month.</li>
 * </ul>
 *
 * <p>Unknown fields are silently ignored — the API may add fields without notice.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OnlinerSaleApartment(
    @JsonProperty("id") Long id,
    @JsonProperty("url") String url,
    @JsonProperty("number_of_rooms") Integer numberOfRooms,
    @JsonProperty("photo") String photo,
    @JsonProperty("price") OnlinerPrice price,
    @JsonProperty("location") OnlinerLocation location,
    @JsonProperty("seller") OnlinerSaleSeller seller,
    @JsonProperty("area") OnlinerSaleArea area,
    @JsonProperty("resale") Boolean resale,
    @JsonProperty("created_at") OffsetDateTime createdAt,
    @JsonProperty("last_time_up") OffsetDateTime lastTimeUp
) {}
