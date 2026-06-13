package com.flatio.integration.onliner.client;

import com.flatio.integration.core.ConnectorTransientException;
import com.flatio.integration.core.ListingConnector;
import com.flatio.integration.core.RawListing;
import com.flatio.integration.onliner.config.OnlinerProperties;
import com.flatio.integration.onliner.dto.OnlinerApartment;
import com.flatio.integration.onliner.dto.OnlinerConvertedPrice;
import com.flatio.integration.onliner.dto.OnlinerSearchResponse;
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
 * Connector for fetching apartment listings from the Onliner API.
 *
 * <p>Supports two fetch strategies:
 * <ul>
 *   <li>{@link #fetchDelta(Instant)} — incremental: pages through results sorted by
 *       {@code last_time_up} descending and stops once an entry is older than the given threshold.</li>
 *   <li>{@link #fetchAll()} — full pass: iterates every available page, re-checking
 *       {@code page.last} on each iteration since the total may change mid-run.</li>
 * </ul>
 *
 * <p>Rate limiting (1 req/s), circuit breaker (opens after 5 failures, stays open 60 s),
 * and retry with exponential backoff (3 attempts: 2 s → 4 s → 8 s) are applied via Resilience4j.
 * HTTP 429 is retried after honouring the {@code Retry-After} header (default 5 s).
 * Each listing is parsed in isolation — a broken entry does not abort the full fetch.
 */
@Service
@Slf4j
public class OnlinerConnector implements ListingConnector {

  private static final long DEFAULT_RETRY_AFTER_SECONDS = 5L;
  private static final long MAX_RETRY_AFTER_SECONDS = 60L;
  private static final String DEAL_TYPE_RENT = "RENT";
  private static final String PROPERTY_TYPE_APARTMENT = "APARTMENT";
  private static final String PROPERTY_TYPE_ROOM = "ROOM";
  private static final String IMGPROXY_ONLINER_HOST = "imgproxy.onliner.by";
  private static final int BASE64_BLOCK_SIZE = 4;

  /**
   * Maps Onliner {@code rent_type} to room count.
   * {@code "room"} (single room for rent, not a full apartment) is intentionally absent — maps to null.
   * Unrecognised values also produce null.
   */
  private static final Map<String, Integer> RENT_TYPE_TO_ROOMS = Map.of(
      "1_room", 1,
      "2_rooms", 2,
      "3_rooms", 3,
      "4_rooms", 4
  );

  private final RestClient restClient;
  private final OnlinerProperties properties;

  public OnlinerConnector(@Qualifier("onlinerRestClient") RestClient restClient,
      OnlinerProperties properties) {
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
   * Fetches listings from the Onliner API.
   *
   * <p>Delegates to {@link #fetchAll()} for backward compatibility with the existing scheduler.
   * Prefer {@link #fetchDelta(Instant)} for incremental syncs once the dedicated sync jobs
   * from issue #104 are in place.
   *
   * @return list of raw listings, never null
   */
  @Override
  @RateLimiter(name = "connector-onliner")
  @CircuitBreaker(name = "connector-onliner")
  @Retry(name = "connector-onliner", fallbackMethod = "fetchFallback")
  public List<RawListing> fetch() {
    return fetchAllInternal();
  }

  /**
   * Fetches all currently active listings from every available page.
   *
   * <p>{@code page.last} is re-read on each iteration — the total may change
   * while pagination is in progress. Rate-limited, circuit-broken, and retried via
   * Resilience4j; on exhausted retries {@link #fetchAllFallback} returns an empty list.
   *
   * @return complete list of raw listings, never null
   */
  @RateLimiter(name = "connector-onliner")
  @CircuitBreaker(name = "connector-onliner")
  @Retry(name = "connector-onliner", fallbackMethod = "fetchAllFallback")
  public List<RawListing> fetchAll() {
    log.info("Full fetch started: source={}", properties.sourceId());
    List<RawListing> result = fetchAllInternal();
    log.info("Full fetch completed: source={}, fetched={}", properties.sourceId(), result.size());
    return result;
  }

  /**
   * Fetches listings published or updated at or after the given timestamp.
   *
   * <p>Pages through results ordered by {@code last_time_up} descending and stops as soon as
   * an entry's {@code last_time_up} is strictly before {@code since}. {@code page.last} is
   * re-read on each iteration. Rate-limited, circuit-broken, and retried via Resilience4j.
   *
   * @param since lower-bound timestamp (exclusive); entries older than this are skipped
   * @return list of recently updated listings, never null
   */
  @RateLimiter(name = "connector-onliner")
  @CircuitBreaker(name = "connector-onliner")
  @Retry(name = "connector-onliner", fallbackMethod = "fetchDeltaFallback")
  public List<RawListing> fetchDelta(Instant since) {
    log.info("Delta fetch started: source={}, since={}", properties.sourceId(), since);
    List<RawListing> result = new ArrayList<>();
    int currentPage = 1;
    int lastPage = 1;
    boolean done = false;
    try {
      do {
        OnlinerSearchResponse response = fetchPage(currentPage);
        if (response == null || response.apartments() == null || response.apartments().isEmpty()) {
          break;
        }
        if (response.page() != null && response.page().last() != null) {
          lastPage = response.page().last();
        }
        for (OnlinerApartment apt : response.apartments()) {
          if (apt.lastTimeUp() != null && apt.lastTimeUp().toInstant().isBefore(since)) {
            done = true;
            break;
          }
          safeAdd(result, apt);
        }
        currentPage++;
      } while (!done && currentPage <= lastPage);
    } catch (HttpClientErrorException.TooManyRequests e) {
      handleRateLimit(e);
    } catch (HttpClientErrorException e) {
      log.error("Non-retryable error on delta fetch: status={}, source={}", e.getStatusCode(), properties.sourceId(), e);
    }
    log.info("Delta fetch completed: source={}, fetched={}", properties.sourceId(), result.size());
    return result;
  }

  // Package-private: Resilience4j AOP proxy requires fallback methods to be accessible from the same package.
  List<RawListing> fetchFallback(Exception e) {
    log.error("All retry attempts exhausted for Onliner fetch: source={}", properties.sourceId(), e);
    return List.of();
  }

  List<RawListing> fetchAllFallback(Exception e) {
    log.error("All retry attempts exhausted for Onliner full fetch: source={}", properties.sourceId(), e);
    return List.of();
  }

  List<RawListing> fetchDeltaFallback(Instant since, Exception e) {
    log.error("All retry attempts exhausted for Onliner delta fetch: source={}, since={}", properties.sourceId(), since, e);
    return List.of();
  }

  private List<RawListing> fetchAllInternal() {
    List<RawListing> result = new ArrayList<>();
    int currentPage = 1;
    int lastPage = 1;
    try {
      do {
        OnlinerSearchResponse response = fetchPage(currentPage);
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
      handleRateLimit(e);
    } catch (HttpClientErrorException e) {
      log.error("Non-retryable error on full fetch: status={}, source={}", e.getStatusCode(), properties.sourceId(), e);
    }
    return result;
  }

  private OnlinerSearchResponse fetchPage(int pageNumber) {
    return restClient.get()
        .uri(uriBuilder -> uriBuilder
            .path(properties.apartmentsPath())
            .queryParam("page", pageNumber)
            .queryParam("limit", properties.pageSize())
            .build())
        .retrieve()
        .body(OnlinerSearchResponse.class);
  }

  private List<RawListing> parseListings(List<OnlinerApartment> apartments) {
    List<RawListing> result = new ArrayList<>();
    for (OnlinerApartment apartment : apartments) {
      safeAdd(result, apartment);
    }
    return result;
  }

  private void safeAdd(List<RawListing> result, OnlinerApartment apartment) {
    try {
      result.add(toRawListing(apartment));
    } catch (Exception e) {
      log.warn("Skipping broken Onliner listing: id={}, error={}", apartment.id(), e.getMessage());
    }
  }

  private RawListing toRawListing(OnlinerApartment apartment) {
    if (apartment.price() == null) {
      throw new IllegalArgumentException("Missing price for apartment id=" + apartment.id());
    }
    Map<String, OnlinerConvertedPrice> converted = apartment.price().converted();
    OnlinerConvertedPrice bynConverted = converted != null ? converted.get("BYN") : null;
    if (bynConverted == null) {
      throw new IllegalArgumentException("Missing BYN converted price for apartment id=" + apartment.id());
    }
    BigDecimal price = new BigDecimal(bynConverted.amount());
    OnlinerConvertedPrice usdConverted = converted.get("USD");
    BigDecimal priceUsd = usdConverted != null ? new BigDecimal(usdConverted.amount()) : null;
    BigDecimal lat = apartment.location() != null ? apartment.location().latitude() : null;
    BigDecimal lon = apartment.location() != null ? apartment.location().longitude() : null;
    String address = apartment.location() != null ? apartment.location().address() : null;
    String resolvedPhoto = resolvePhotoUrl(apartment.photo());
    List<String> photos = resolvedPhoto != null ? List.of(resolvedPhoto) : List.of();
    Instant publishedAt = apartment.lastTimeUp() != null ? apartment.lastTimeUp().toInstant() : null;
    Integer rooms = mapRentTypeToRooms(apartment.rentType());
    Boolean isOwner = apartment.contact() != null ? apartment.contact().owner() : null;

    return new RawListing(
        String.valueOf(apartment.id()),
        buildTitle(address),
        null,
        DEAL_TYPE_RENT,
        mapRentTypeToPropertyType(apartment.rentType()),
        price,
        "BYN",
        priceUsd,
        rooms,
        null,
        null,
        null,
        address,
        lat,
        lon,
        null,
        apartment.url(),
        publishedAt,
        photos,
        isOwner,
        null
    );
  }

  private static Integer mapRentTypeToRooms(String rentType) {
    if (rentType == null) {
      return null;
    }
    return RENT_TYPE_TO_ROOMS.get(rentType);
  }

  /**
   * Maps Onliner {@code rent_type} to a property type string.
   *
   * <p>{@code "room"} (single room for rent) maps to {@code "ROOM"}.
   * All apartment rent types ({@code "1_room"}, {@code "2_rooms"}, etc.) map to {@code "APARTMENT"}.
   *
   * @param rentType the Onliner rent_type value, may be null
   * @return {@code "ROOM"} if rent_type is {@code "room"}, {@code "APARTMENT"} otherwise
   */
  private static String mapRentTypeToPropertyType(String rentType) {
    if ("room".equals(rentType)) {
      return PROPERTY_TYPE_ROOM;
    }
    return PROPERTY_TYPE_APARTMENT;
  }

  /**
   * Resolves the original photo URL from an Onliner imgproxy-wrapped URL.
   *
   * <p>Onliner imgproxy splits the base64-encoded original URL across multiple path segments
   * of 16 characters each (e.g. {@code /aHR0cHM6Ly9jb250/ZW50Lm9ubGluZXIu/...}).
   * Transform parameters such as {@code w:600}, {@code h:400}, {@code dpr:2} always contain
   * a colon and appear before the base64 chunks. All segments after the last colon-containing
   * segment are joined and decoded as a single base64 string.
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

      // Transform params (w:600, h:400, dpr:2) always contain ':'; base64 chunks never do.
      // Find the last transform param — everything after it forms the base64 payload.
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

      // Add padding if needed — base64 length must be a multiple of BASE64_BLOCK_SIZE
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
    return "Квартира на Onliner";
  }

  private void handleRateLimit(HttpClientErrorException.TooManyRequests e) {
    long retryAfterSeconds = parseRetryAfterSeconds(e.getResponseHeaders());
    log.warn("Rate limited by Onliner (429): source={}, retryAfterSeconds={}", properties.sourceId(), retryAfterSeconds);
    sleepQuietly(retryAfterSeconds * 1000L);
    throw new ConnectorTransientException("Rate limited: source=" + properties.sourceId(), e);
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

  private void sleepQuietly(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
