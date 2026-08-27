package com.flatio.integration.nominatim.client;

import com.flatio.integration.nominatim.config.NominatimProperties;
import com.flatio.integration.nominatim.dto.NominatimReverseResponse;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Reverse-geocoding client backed by the Nominatim (OpenStreetMap) public API.
 *
 * <p>Resolves geographic coordinates to a human-readable settlement name following the
 * OSM address hierarchy: city → town → village/hamlet (the latter prefixed with
 * {@code nominatim.village-prefix}, issue #425 — depends on {@code nominatim.language} rather
 * than being hardcoded, so a non-Russian language config can supply its own prefix).
 *
 * <p>Rate limiting (1 req/s) is enforced programmatically via Resilience4j so that only
 * actual HTTP calls consume a permit — cache hits bypass the limiter entirely.
 *
 * <p>Responses are cached in memory keyed by coordinates rounded to
 * {@value #COORD_SCALE} decimal places (~111 m precision), which avoids redundant
 * requests for nearby listings in the same neighbourhood. Backed by Caffeine rather than a
 * plain {@code ConcurrentHashMap} (issue #420, same class of bug as {@code UserStatusCache}
 * — issue #399): a plain map never evicts an entry once written, so distinct coordinate cells
 * accumulate in memory for the lifetime of the process. Entries expire
 * {@value #CACHE_TTL_DAYS} days after being written and are bounded by {@link #CACHE_MAX_SIZE};
 * a long TTL is safe here because a settlement's name for a given location does not change.
 */
@Service
@Slf4j
public class NominatimClient {

  private static final int COORD_SCALE = 3;
  private static final long CACHE_TTL_DAYS = 30;
  private static final long CACHE_MAX_SIZE = 50_000;

  private final RestClient restClient;
  private final RateLimiter rateLimiter;
  private final String language;
  private final String villagePrefix;
  private final Map<String, Optional<String>> cache = Caffeine.newBuilder()
      .expireAfterWrite(Duration.ofDays(CACHE_TTL_DAYS))
      .maximumSize(CACHE_MAX_SIZE)
      .<String, Optional<String>>build()
      .asMap();

  public NominatimClient(
      @Qualifier("nominatimRestClient") RestClient restClient,
      RateLimiterRegistry rateLimiterRegistry,
      NominatimProperties properties
  ) {
    this.restClient = restClient;
    this.rateLimiter = rateLimiterRegistry.rateLimiter("nominatim");
    this.language = properties.language();
    this.villagePrefix = properties.villagePrefix();
  }

  /**
   * Resolves the settlement name for the given coordinates.
   *
   * <p>Cache is checked first; on a miss the Nominatim API is called under the rate limiter.
   * Both successful and empty results are cached to prevent hammering the API for locations
   * that consistently return no match.
   *
   * @param latitude  geographic latitude of the listing
   * @param longitude geographic longitude of the listing
   * @return settlement name, or empty if Nominatim returned no usable address
   */
  public Optional<String> reverseGeocode(BigDecimal latitude, BigDecimal longitude) {
    String cacheKey = buildCacheKey(latitude, longitude);
    Optional<String> cached = cache.get(cacheKey);
    if (cached != null) {
      return cached;
    }
    Optional<String> result = rateLimiter.executeSupplier(() -> fetchCity(latitude, longitude));
    cache.put(cacheKey, result);
    return result;
  }

  private Optional<String> fetchCity(BigDecimal latitude, BigDecimal longitude) {
    NominatimReverseResponse response = restClient.get()
        .uri(uriBuilder -> uriBuilder
            .path("/reverse")
            .queryParam("lat", latitude)
            .queryParam("lon", longitude)
            .queryParam("format", "json")
            .queryParam("accept-language", language)
            .build())
        .retrieve()
        .body(NominatimReverseResponse.class);

    if (response == null || response.address() == null) {
      log.debug("Nominatim returned empty response for lat={}, lon={}", latitude, longitude);
      return Optional.empty();
    }
    return Optional.ofNullable(resolveCity(response.address()));
  }

  private String resolveCity(NominatimReverseResponse.NominatimAddress address) {
    if (address.city() != null) {
      return address.city();
    }
    if (address.town() != null) {
      return address.town();
    }
    String settlement = address.village() != null ? address.village() : address.hamlet();
    if (settlement == null) {
      return null;
    }
    return address.county() != null
        ? villagePrefix + settlement + ", " + address.county()
        : villagePrefix + settlement;
  }

  private String buildCacheKey(BigDecimal latitude, BigDecimal longitude) {
    return latitude.setScale(COORD_SCALE, RoundingMode.HALF_UP)
        + "," + longitude.setScale(COORD_SCALE, RoundingMode.HALF_UP);
  }
}
