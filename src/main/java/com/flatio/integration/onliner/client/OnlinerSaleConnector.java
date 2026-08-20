package com.flatio.integration.onliner.client;

import com.flatio.integration.core.ConnectorTransientException;
import com.flatio.integration.core.ListingConnector;
import com.flatio.integration.core.RawListing;
import com.flatio.integration.onliner.config.OnlinerSaleProperties;
import com.flatio.integration.onliner.dto.OnlinerConvertedPrice;
import com.flatio.integration.onliner.dto.OnlinerSaleApartment;
import com.flatio.integration.onliner.dto.OnlinerSaleSearchResponse;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Connector for fetching apartment sale listings from the Onliner purchase API
 * ({@code pk.api.onliner.by}).
 *
 * <p>Supports two fetch strategies:
 * <ul>
 *   <li>{@link #fetchDelta(Instant)} — incremental: pages through results sorted by
 *       {@code last_time_up} descending and stops once an entry is older than the given threshold.</li>
 *   <li>{@link #fetchAll()} — full pass: iterates every available page.</li>
 * </ul>
 *
 * <p>All returned listings have {@code dealType = "SELL"}.
 * Rate limiting (1 req/s), circuit breaker (opens after 5 failures, stays open 60 s),
 * and retry with exponential backoff (3 attempts: 2 s → 4 s → 8 s) are applied via Resilience4j.
 */
@Service
@Slf4j
public class OnlinerSaleConnector implements ListingConnector {

  private static final long DEFAULT_RETRY_AFTER_SECONDS = 5L;
  private static final long MAX_RETRY_AFTER_SECONDS = 60L;
  private static final String DEAL_TYPE_SELL = "SELL";
  private static final String PROPERTY_TYPE_APARTMENT = "APARTMENT";
  private static final String IMGPROXY_ONLINER_HOST = "imgproxy.onliner.by";
  private static final int BASE64_BLOCK_SIZE = 4;

  private final RestClient restClient;
  private final OnlinerSaleProperties properties;

  public OnlinerSaleConnector(@Qualifier("onlinerSaleRestClient") RestClient restClient,
      OnlinerSaleProperties properties) {
    this.restClient = restClient;
    this.properties = properties;
  }

  @Override
  public String getSourceId() {
    return properties.sourceId();
  }

  @Override
  public String getSupportedRegionCode() {
    return properties.regionCode();
  }

  /**
   * Fetches sale listings from the Onliner purchase API.
   *
   * <p>Delegates to {@link #fetchAll()} for use by the existing scheduler contract.
   * Prefer {@link #fetchDelta(Instant)} for incremental syncs.
   *
   * @return list of raw sale listings, never null
   */
  @Override
  @RateLimiter(name = "connector-onliner-sale")
  @CircuitBreaker(name = "connector-onliner-sale")
  @Retry(name = "connector-onliner-sale", fallbackMethod = "fetchFallback")
  public List<RawListing> fetch() {
    return fetchAllInternal();
  }

  /**
   * Fetches all currently active sale listings from every available page.
   *
   * <p>Rate-limited, circuit-broken, and retried via Resilience4j.
   *
   * @return complete list of raw sale listings, never null
   */
  @RateLimiter(name = "connector-onliner-sale")
  @CircuitBreaker(name = "connector-onliner-sale")
  @Retry(name = "connector-onliner-sale", fallbackMethod = "fetchAllFallback")
  public List<RawListing> fetchAll() {
    log.info("Full fetch started: source={}", properties.sourceId());
    List<RawListing> result = fetchAllInternal();
    log.info("Full fetch completed: source={}, fetched={}", properties.sourceId(), result.size());
    return result;
  }

  /**
   * Fetches sale listings published or updated at or after the given timestamp.
   *
   * <p>Pages through results ordered by {@code last_time_up} descending and stops as soon as
   * an entry's {@code last_time_up} is strictly before {@code since}.
   *
   * @param since lower-bound timestamp (exclusive); entries older than this are skipped
   * @return list of recently updated sale listings, never null
   */
  @RateLimiter(name = "connector-onliner-sale")
  @CircuitBreaker(name = "connector-onliner-sale")
  @Retry(name = "connector-onliner-sale", fallbackMethod = "fetchDeltaFallback")
  public List<RawListing> fetchDelta(Instant since) {
    log.info("Delta fetch started: source={}, since={}", properties.sourceId(), since);
    List<RawListing> result = new ArrayList<>();
    int currentPage = 1;
    int lastPage = 1;
    boolean done = false;
    try {
      do {
        OnlinerSaleSearchResponse response = fetchPage(currentPage);
        if (response == null || response.apartments() == null || response.apartments().isEmpty()) {
          break;
        }
        if (response.page() != null && response.page().last() != null) {
          lastPage = response.page().last();
        }
        for (OnlinerSaleApartment apt : response.apartments()) {
          if (apt.lastTimeUp() != null && apt.lastTimeUp().toInstant().isBefore(since)) {
            done = true;
            break;
          }
          safeAdd(result, apt);
        }
        currentPage++;
      } while (!done && currentPage <= lastPage);
    } catch (HttpClientErrorException.TooManyRequests e) {
      handleRateLimitOrKeepPartial(result, e);
    } catch (HttpClientErrorException e) {
      log.error("Non-retryable error on delta fetch: status={}, source={}", e.getStatusCode(), properties.sourceId(), e);
    }
    log.info("Delta fetch completed: source={}, fetched={}", properties.sourceId(), result.size());
    return result;
  }

  // Package-private: Resilience4j AOP proxy requires fallback methods to be accessible from the same package.
  List<RawListing> fetchFallback(Exception e) {
    log.error("All retry attempts exhausted for Onliner sale fetch: source={}", properties.sourceId(), e);
    return List.of();
  }

  List<RawListing> fetchAllFallback(Exception e) {
    log.error("All retry attempts exhausted for Onliner sale full fetch: source={}", properties.sourceId(), e);
    return List.of();
  }

  List<RawListing> fetchDeltaFallback(Instant since, Exception e) {
    log.error("All retry attempts exhausted for Onliner sale delta fetch: source={}, since={}", properties.sourceId(), since, e);
    return List.of();
  }

  private List<RawListing> fetchAllInternal() {
    List<RawListing> result = new ArrayList<>();
    int currentPage = 1;
    int lastPage = 1;
    try {
      do {
        OnlinerSaleSearchResponse response = fetchPage(currentPage);
        if (response == null || response.apartments() == null || response.apartments().isEmpty()) {
          break;
        }
        if (response.page() != null && response.page().last() != null) {
          lastPage = response.page().last();
        }
        result.addAll(parseListings(response.apartments()));
        currentPage++;
      } while (currentPage <= lastPage);
    } catch (HttpClientErrorException.TooManyRequests e) {
      handleRateLimitOrKeepPartial(result, e);
    } catch (HttpClientErrorException e) {
      log.error("Non-retryable error on full fetch: status={}, source={}", e.getStatusCode(), properties.sourceId(), e);
    }
    return result;
  }

  private OnlinerSaleSearchResponse fetchPage(int pageNumber) {
    return restClient.get()
        .uri(uriBuilder -> uriBuilder
            .path(properties.apartmentsPath())
            .queryParam("page", pageNumber)
            .queryParam("limit", properties.pageSize())
            .build())
        .retrieve()
        .body(OnlinerSaleSearchResponse.class);
  }

  private List<RawListing> parseListings(List<OnlinerSaleApartment> apartments) {
    List<RawListing> result = new ArrayList<>();
    for (OnlinerSaleApartment apartment : apartments) {
      safeAdd(result, apartment);
    }
    return result;
  }

  private void safeAdd(List<RawListing> result, OnlinerSaleApartment apartment) {
    try {
      result.add(toRawListing(apartment));
    } catch (Exception e) {
      log.warn("Skipping broken Onliner sale listing: id={}, error={}", apartment.id(), e.getMessage());
    }
  }

  private RawListing toRawListing(OnlinerSaleApartment apartment) {
    BigDecimal price;
    BigDecimal priceUsd;
    boolean isNegotiable;
    if (apartment.price() == null) {
      price = BigDecimal.ZERO;
      priceUsd = null;
      isNegotiable = true;
    } else {
      Map<String, OnlinerConvertedPrice> converted = apartment.price().converted();
      OnlinerConvertedPrice bynConverted = converted != null ? converted.get("BYN") : null;
      if (bynConverted == null) {
        throw new IllegalArgumentException("Missing BYN converted price for sale apartment id=" + apartment.id());
      }
      price = new BigDecimal(bynConverted.amount());
      isNegotiable = price.compareTo(BigDecimal.ZERO) == 0;
      OnlinerConvertedPrice usdConverted = converted.get("USD");
      priceUsd = usdConverted != null ? new BigDecimal(usdConverted.amount()) : null;
    }
    BigDecimal lat = apartment.location() != null ? apartment.location().latitude() : null;
    BigDecimal lon = apartment.location() != null ? apartment.location().longitude() : null;
    String address = apartment.location() != null ? apartment.location().address() : null;
    String city = resolveCity(address);
    // Diagnostic for #327 (address arriving/persisting empty): logs the address exactly as
    // parsed from the source response, before it enters RawListing/persistence, so a future
    // recurrence can be localized to fetch-time (empty here) vs. mapping/persist-time (non-empty
    // here but empty in the DB).
    log.debug("Onliner sale apartment parsed: id={}, hasLocation={}, address={}",
        apartment.id(), apartment.location() != null, address);
    String resolvedPhoto = resolvePhotoUrl(apartment.photo());
    List<String> photos = resolvedPhoto != null ? List.of(resolvedPhoto) : List.of();
    Instant publishedAt = apartment.createdAt() != null ? apartment.createdAt().toInstant() : null;
    Boolean isOwner = apartment.seller() != null ? "owner".equals(apartment.seller().type()) : null;

    return new RawListing(
        String.valueOf(apartment.id()),
        buildTitle(address),
        null,
        DEAL_TYPE_SELL,
        PROPERTY_TYPE_APARTMENT,
        price,
        "BYN",
        priceUsd,
        null,
        apartment.numberOfRooms(),
        null,
        null,
        null,
        address,
        lat,
        lon,
        city,
        apartment.url(),
        publishedAt,
        photos,
        isOwner,
        null,
        isNegotiable
    );
  }

  /**
   * Derives the city from an Onliner {@code location.address} string.
   *
   * <p>The Onliner API has no dedicated city field — the city name is always the leading
   * segment of {@code address} (e.g. {@code "Минск, улица Кедышко, 3"} → {@code "Минск"},
   * or the whole string when no street follows, e.g. {@code "Гродно"} → {@code "Гродно"}).
   *
   * @param address raw address string from {@code location.address}, may be null
   * @return the leading comma-separated segment trimmed, or null if address is null/blank
   */
  private static String resolveCity(String address) {
    if (address == null || address.isBlank()) {
      return null;
    }
    int commaIndex = address.indexOf(',');
    String city = commaIndex >= 0 ? address.substring(0, commaIndex) : address;
    city = city.trim();
    return city.isEmpty() ? null : city;
  }

  /**
   * Resolves the original photo URL from an Onliner imgproxy-wrapped URL.
   *
   * <p>Onliner imgproxy splits the base64-encoded original URL across multiple path segments.
   * Transform parameters (e.g. {@code w:600}, {@code h:400}) always contain a colon;
   * base64 chunks never do. All segments after the last colon-containing segment are
   * joined and decoded.
   *
   * @param photoUrl raw photo URL from Onliner API, may be null
   * @return decoded original URL, the input URL unchanged if not an imgproxy URL, or null on failure
   */
  private String resolvePhotoUrl(String photoUrl) {
    if (photoUrl == null) {
      return null;
    }
    if (!photoUrl.contains(IMGPROXY_ONLINER_HOST)) {
      return photoUrl;
    }
    try {
      String[] segments = photoUrl.split("/", -1);
      int lastTransformIdx = -1;
      for (int i = 0; i < segments.length; i++) {
        if (segments[i].contains(":")) {
          lastTransformIdx = i;
        }
      }
      if (lastTransformIdx < 0 || lastTransformIdx >= segments.length - 1) {
        log.warn("Unexpected imgproxy URL format, cannot extract base64 segments: url={}", photoUrl);
        return null;
      }
      StringBuilder base64Builder = new StringBuilder();
      for (int i = lastTransformIdx + 1; i < segments.length; i++) {
        base64Builder.append(segments[i]);
      }
      String base64 = base64Builder.toString();
      int padLen = (BASE64_BLOCK_SIZE - base64.length() % BASE64_BLOCK_SIZE) % BASE64_BLOCK_SIZE;
      base64 = base64 + "=".repeat(padLen);
      byte[] decoded = Base64.getUrlDecoder().decode(base64);
      return new String(decoded, StandardCharsets.UTF_8);
    } catch (Exception e) {
      log.warn("Failed to decode imgproxy photo URL: url={}, error={}", photoUrl, e.getMessage());
      return null;
    }
  }

  private String buildTitle(String address) {
    if (address != null && !address.isBlank()) {
      return address;
    }
    return "Квартира на продажу (Onliner)";
  }

  /**
   * Handles a 429 response encountered mid-pagination.
   *
   * <p>If no listings have been collected yet, throws so Resilience4j retries the whole fetch
   * from page 1. If some pages already succeeded, the partial result is kept and returned as-is
   * instead of retrying — a full retry would discard already-collected data with no guarantee
   * of getting further this time, whereas the next scheduled sync run naturally covers the rest
   * (issue #370).
   *
   * @param result the listings collected so far in this fetch, never null
   * @param e      the 429 response caught by the caller
   */
  private void handleRateLimitOrKeepPartial(List<RawListing> result, HttpClientErrorException.TooManyRequests e) {
    long retryAfterSeconds = parseRetryAfterSeconds(e.getResponseHeaders());
    if (result.isEmpty()) {
      log.warn("Rate limited by Onliner sale API (429): source={}, retryAfterSeconds={} — Resilience4j will back off",
          properties.sourceId(), retryAfterSeconds);
      throw new ConnectorTransientException("Rate limited: source=" + properties.sourceId(), e);
    }
    log.warn("Rate limited by Onliner sale API (429) after collecting {} listing(s) mid-pagination — "
        + "returning partial result instead of retrying from page 1: source={}",
        result.size(), properties.sourceId());
  }

  long parseRetryAfterSeconds(HttpHeaders headers) {
    if (headers == null) {
      return DEFAULT_RETRY_AFTER_SECONDS;
    }
    String retryAfter = headers.getFirst(HttpHeaders.RETRY_AFTER);
    if (retryAfter == null) {
      return DEFAULT_RETRY_AFTER_SECONDS;
    }
    try {
      return Math.min(Long.parseLong(retryAfter.trim()), MAX_RETRY_AFTER_SECONDS);
    } catch (NumberFormatException e) {
      return DEFAULT_RETRY_AFTER_SECONDS;
    }
  }
}
