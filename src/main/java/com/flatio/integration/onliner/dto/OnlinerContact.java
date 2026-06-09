package com.flatio.integration.onliner.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Contact information attached to an Onliner apartment listing.
 *
 * <p>Indicates whether the listing was posted directly by the property owner
 * ({@code owner = true}) or by an agency/intermediary ({@code owner = false}).
 */
public record OnlinerContact(
    @JsonProperty("owner") boolean owner
) {}
