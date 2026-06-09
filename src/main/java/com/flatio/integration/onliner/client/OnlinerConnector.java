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
import java.time.Instant;
import java.util.ArrayList;
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
  private static final String DEAL_TYPE_RENT = "RENT";
  private static final String PROPERTY_TYPE_APARTMENT = "APARTMENT";
  private static final String PROPERTY_TYPE_ROOM = "ROOM";

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
    List<String> photos = apartment.photo() != null ? List.of(apartment.photo()) : List.of();
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
        isOwner
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

  private long parseRetryAfterSeconds(HttpHeaders headers) {
    if (headers == null) {
      return DEFAULT_RETRY_AFTER_SECONDS;
    }
    String retryAfter = headers.getFirst(HttpHeaders.RETRY_AFTER);
    if (retryAfter == null) {
      return DEFAULT_RETRY_AFTER_SECONDS;
    }
    try {
      return Long.parseLong(retryAfter.trim());
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
