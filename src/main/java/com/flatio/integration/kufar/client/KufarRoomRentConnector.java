package com.flatio.integration.kufar.client;

import com.flatio.integration.core.ListingConnector;
import com.flatio.integration.core.RawListing;
import com.flatio.integration.kufar.config.KufarProperties;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Connector for fetching room rental listings from the Kufar JSON API.
 *
 * <p>All listings have {@code dealType = RENT} and {@code propertyType = ROOM}.
 */
@Service
@Slf4j
public class KufarRoomRentConnector implements ListingConnector {

  private static final String DEAL_TYPE_RENT = "RENT";
  private static final String PROPERTY_TYPE_ROOM = "ROOM";
  private static final String FALLBACK_TITLE = "Комната в аренду (Kufar.by)";

  private final KufarApiClient kufarApiClient;
  private final KufarProperties properties;

  public KufarRoomRentConnector(KufarApiClient kufarApiClient, KufarProperties properties) {
    this.kufarApiClient = kufarApiClient;
    this.properties = properties;
  }

  @Override
  public String getSourceId() {
    return properties.roomRent().sourceId();
  }

  @Override
  public String getSupportedRegionCode() {
    return properties.roomRent().regionCode();
  }

  /**
   * Fetches room rental listings from Kufar.
   *
   * @return list of raw listings, never null, may be empty on source error
   */
  @Override
  @RateLimiter(name = "connector-kufar")
  @CircuitBreaker(name = "connector-kufar")
  @Retry(name = "connector-kufar", fallbackMethod = "fetchFallback")
  public List<RawListing> fetch() {
    return kufarApiClient.fetchAll(properties.roomRent(), DEAL_TYPE_RENT, PROPERTY_TYPE_ROOM, FALLBACK_TITLE);
  }

  /**
   * Fetches room rental listings published at or after the given timestamp.
   *
   * @param since lower-bound timestamp (exclusive)
   * @return list of recently published listings, never null
   */
  @RateLimiter(name = "connector-kufar")
  @CircuitBreaker(name = "connector-kufar")
  @Retry(name = "connector-kufar", fallbackMethod = "fetchDeltaFallback")
  public List<RawListing> fetchDelta(Instant since) {
    log.info("Delta fetch started: source={}, since={}", properties.roomRent().sourceId(), since);
    List<RawListing> result = kufarApiClient.fetchDelta(
        properties.roomRent(), since, DEAL_TYPE_RENT, PROPERTY_TYPE_ROOM, FALLBACK_TITLE);
    log.info("Delta fetch completed: source={}, fetched={}", properties.roomRent().sourceId(), result.size());
    return result;
  }

  List<RawListing> fetchFallback(Exception e) {
    log.error("All retry attempts exhausted for KufarRoomRent fetch: source={}", properties.roomRent().sourceId(), e);
    return List.of();
  }

  List<RawListing> fetchDeltaFallback(Instant since, Exception e) {
    log.error("All retry attempts exhausted for KufarRoomRent delta fetch: source={}, since={}",
        properties.roomRent().sourceId(), since, e);
    return List.of();
  }
}
