package com.flatio.integration.kufar.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Single listing ad returned by the Kufar search API.
 *
 * <p>Price is in BYN kopecks (hundredths of BYN) — divide by 100 to get whole BYN.
 * Property attributes (rooms, floor, total floors, area) are in {@code adParameters}
 * ({@code ad_parameters} JSON field), keyed by the machine {@code p} field.
 * {@code accountParameters} ({@code account_parameters}) carries seller profile fields plus —
 * despite the name — the listing's street-level {@code address} entry when the seller provided
 * one (issue #334); {@link com.flatio.integration.kufar.client.KufarApiClient} reads it as the
 * primary address source.
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
    @JsonProperty("ad_parameters") List<KufarAdParameter> adParameters,
    @JsonProperty("company_ad") Boolean companyAd,
    List<KufarImage> images,
    @JsonProperty("list_time") String listTime
) {}
