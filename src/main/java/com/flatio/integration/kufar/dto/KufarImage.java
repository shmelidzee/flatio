package com.flatio.integration.kufar.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Image entry for a Kufar listing.
 *
 * <p>{@code path} is a relative CDN path returned by the Kufar API
 * (e.g. {@code adim1/{uuid}.jpg}). The full URL is constructed by
 * {@code KufarApiClient} by prepending the configured
 * {@code connector.kufar.photo-cdn-base-url} value.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KufarImage(String id, String path) {}
