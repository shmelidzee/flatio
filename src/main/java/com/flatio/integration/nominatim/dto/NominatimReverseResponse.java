package com.flatio.integration.nominatim.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response DTO for the Nominatim reverse-geocoding endpoint.
 *
 * <p>Maps the fields relevant for settlement resolution. Fields not used by the application
 * are ignored by Jackson.
 */
public record NominatimReverseResponse(
    @JsonProperty("display_name") String displayName,
    @JsonProperty("address") NominatimAddress address
) {

  /**
   * Nested address object returned by Nominatim.
   *
   * <p>Settlement hierarchy from most to least specific:
   * city → town → village → hamlet. The {@code county} field provides the district context
   * used when formatting village or hamlet names.
   */
  public record NominatimAddress(
      @JsonProperty("city") String city,
      @JsonProperty("town") String town,
      @JsonProperty("village") String village,
      @JsonProperty("hamlet") String hamlet,
      @JsonProperty("county") String county,
      @JsonProperty("state") String state
  ) {}
}
