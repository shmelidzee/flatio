package com.flatio.integration.kufar.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Single listing ad returned by the Kufar search API.
 *
 * <p>Price is in BYN. {@code accountParameters} carries structured attributes
 * (rooms, floor, area, geopoint) as key-value pairs.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KufarAd(
    @JsonProperty("ad_id") Long adId,
    String subject,
    String body,
    @JsonProperty("price_byn") Long priceByn,
    String currency,
    @JsonProperty("ad_link") String adLink,
    KufarAccount account,
    @JsonProperty("account_parameters") List<KufarAdParameter> accountParameters,
    List<KufarImage> images,
    @JsonProperty("list_time") String listTime
) {}
