package com.flatio.integration.onliner.client;

import com.flatio.integration.core.ConnectorTransientException;
import com.flatio.integration.core.ListingConnector;
import com.flatio.integration.core.RawListing;
import com.flatio.integration.onliner.dto.OnlinerApartment;
import com.flatio.integration.onliner.dto.OnlinerSearchResponse;
import com.flatio.integration.onliner.config.OnlinerProperties;
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

/**
 * Connector for fetching apartment listings from the Onliner API.
 *
 * <p>Implements rate limiting (1 req/s), circuit breaker (opens after 5 consecutive
 * failures, stays open 60 s), and retry with exponential backoff (3 attempts: 2s → 4s → 8s)
 * via Resilience4j. HTTP 429 is retried after honoring the {@code Retry-After} header
 * (default 5 s). Other HTTP 4xx errors are logged and treated as non-retryable.
 * HTTP 5xx propagates for retry and circuit-breaker tracking.
 * Each listing is parsed in isolation — a broken entry does not abort the full fetch.
 */
@Service
@Slf4j
public class OnlinerConnector implements ListingConnector {

  private static final long DEFAULT_RETRY_AFTER_SECONDS = 5L;
  private static final String DEAL_TYPE_RENT = "RENT";
  private static final String PROPERTY_TYPE_APARTMENT = "APARTMENT";

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
   * Fetches apartment listings from the Onliner API.
   *
   * <p>Rate-limited to 1 request/second. Circuit breaker opens after 5 consecutive failures
   * and stays open for 60 seconds — when open, {@link io.github.resilience4j.circuitbreaker.CallNotPermittedException}
   * propagates to the caller. Retries up to 3 times with exponential backoff on HTTP 5xx
   * or 429 responses. After all retries are exhausted, {@link #fetchFallback} is invoked
   * and returns an empty list.
   *
   * @return list of raw listings, never null
   */
  @Override
  @RateLimiter(name = "connector-onliner")
  @CircuitBreaker(name = "connector-onliner")
  @Retry(name = "connector-onliner", fallbackMethod = "fetchFallback")
  public List<RawListing> fetch() {
    log.info("Fetching listings from Onliner: source={}, region={}", properties.sourceId(), properties.regionCode());
    try {
      OnlinerSearchResponse response = restClient.get()
          .uri(uriBuilder -> uriBuilder
              .path(properties.apartmentsPath())
              .queryParam("page", 1)
              .queryParam("limit", properties.pageSize())
              .build())
          .retrieve()
          .body(OnlinerSearchResponse.class);

      if (response == null || response.apartments() == null) {
        log.warn("Empty or null response from Onliner API: source={}", properties.sourceId());
        return List.of();
      }

      log.info("Received {} apartments from Onliner", response.apartments().size());
      return parseListings(response.apartments());

    } catch (HttpClientErrorException.TooManyRequests e) {
      long retryAfterSeconds = parseRetryAfterSeconds(e.getResponseHeaders());
      log.warn("Rate limited by Onliner (429): source={}, retryAfterSeconds={}", properties.sourceId(), retryAfterSeconds);
      sleepQuietly(retryAfterSeconds * 1000L);
      throw new ConnectorTransientException("Rate limited: source=" + properties.sourceId(), e);

    } catch (HttpClientErrorException e) {
      log.error("Non-retryable client error from Onliner: status={}, source={}", e.getStatusCode(), properties.sourceId(), e);
      return List.of();
    }
  }

  /**
   * Fallback invoked by Resilience4j Retry after all retry attempts are exhausted.
   *
   * @param e the exception that triggered fallback
   * @return empty list — graceful degradation when source is unavailable
   */
  List<RawListing> fetchFallback(Exception e) {
    log.error("All retry attempts exhausted for Onliner: source={}", properties.sourceId(), e);
    return List.of();
  }

  private List<RawListing> parseListings(List<OnlinerApartment> apartments) {
    List<RawListing> result = new ArrayList<>();
    for (OnlinerApartment apartment : apartments) {
      try {
        result.add(toRawListing(apartment));
      } catch (Exception e) {
        log.warn("Skipping broken Onliner listing: id={}, error={}", apartment.id(), e.getMessage());
      }
    }
    return result;
  }

  private RawListing toRawListing(OnlinerApartment apartment) {
    if (apartment.price() == null) {
      throw new IllegalArgumentException("Missing price for apartment id=" + apartment.id());
    }
    BigDecimal price = new BigDecimal(apartment.price().amount());
    String currency = apartment.price().currency();
    BigDecimal lat = apartment.location() != null ? apartment.location().latitude() : null;
    BigDecimal lon = apartment.location() != null ? apartment.location().longitude() : null;
    String address = apartment.location() != null ? apartment.location().address() : null;
    List<String> photos = apartment.photo() != null ? List.of(apartment.photo()) : List.of();
    Instant publishedAt = apartment.lastTimeUp() != null ? apartment.lastTimeUp().toInstant() : null;
    String title = buildTitle(address);

    return new RawListing(
        String.valueOf(apartment.id()),
        title,
        null,
        DEAL_TYPE_RENT,
        PROPERTY_TYPE_APARTMENT,
        price,
        currency,
        null,
        null,
        null,
        null,
        address,
        lat,
        lon,
        null,
        apartment.url(),
        publishedAt,
        photos
    );
  }

  private String buildTitle(String address) {
    if (address != null && !address.isBlank()) {
      return address;
    }
    return "Квартира на Onliner";
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
