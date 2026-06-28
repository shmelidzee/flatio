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
 * Connector for fetching house/cottage rental listings from the Kufar JSON API.
 *
 * <p>All listings have {@code dealType = RENT} and {@code propertyType = HOUSE}.
 */
@Service
@Slf4j
public class KufarHouseRentConnector implements ListingConnector {

  private static final String DEAL_TYPE_RENT = "RENT";
  private static final String PROPERTY_TYPE_HOUSE = "HOUSE";
  private static final String FALLBACK_TITLE = "Дом в аренду (Kufar.by)";

  private final KufarApiClient kufarApiClient;
  private final KufarProperties properties;

  public KufarHouseRentConnector(KufarApiClient kufarApiClient, KufarProperties properties) {
    this.kufarApiClient = kufarApiClient;
    this.properties = properties;
  }

  @Override
  public String getSourceId() {
    return properties.houseRent().sourceId();
  }

  @Override
  public String getSupportedRegionCode() {
    return properties.houseRent().regionCode();
  }

  /**
   * Fetches house rental listings from Kufar.
   *
   * @return list of raw listings, never null, may be empty on source error
   */
  @Override
  @RateLimiter(name = "connector-kufar")
  @CircuitBreaker(name = "connector-kufar")
  @Retry(name = "connector-kufar", fallbackMethod = "fetchFallback")
  public List<RawListing> fetch() {
    return kufarApiClient.fetchAll(properties.houseRent(), DEAL_TYPE_RENT, PROPERTY_TYPE_HOUSE, FALLBACK_TITLE);
  }

  /**
   * Fetches house rental listings published at or after the given timestamp.
   *
   * @param since lower-bound timestamp (exclusive)
   * @return list of recently published listings, never null
   */
  @RateLimiter(name = "connector-kufar")
  @CircuitBreaker(name = "connector-kufar")
  @Retry(name = "connector-kufar", fallbackMethod = "fetchDeltaFallback")
  public List<RawListing> fetchDelta(Instant since) {
    log.info("Delta fetch started: source={}, since={}", properties.houseRent().sourceId(), since);
    List<RawListing> result = kufarApiClient.fetchDelta(
        properties.houseRent(), since, DEAL_TYPE_RENT, PROPERTY_TYPE_HOUSE, FALLBACK_TITLE);
    log.info("Delta fetch completed: source={}, fetched={}", properties.houseRent().sourceId(), result.size());
    return result;
  }

  List<RawListing> fetchFallback(Exception e) {
    log.error("All retry attempts exhausted for KufarHouseRent fetch: source={}", properties.houseRent().sourceId(), e);
    return List.of();
  }

  List<RawListing> fetchDeltaFallback(Instant since, Exception e) {
    log.error("All retry attempts exhausted for KufarHouseRent delta fetch: source={}, since={}",
        properties.houseRent().sourceId(), since, e);
    return List.of();
  }
}
