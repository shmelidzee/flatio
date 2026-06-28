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
 * Connector for fetching room sale listings from the Kufar JSON API.
 *
 * <p>All listings have {@code dealType = SELL} and {@code propertyType = ROOM}.
 */
@Service
@Slf4j
public class KufarRoomSaleConnector implements ListingConnector {

  private static final String DEAL_TYPE_SELL = "SELL";
  private static final String PROPERTY_TYPE_ROOM = "ROOM";
  private static final String FALLBACK_TITLE = "Комната на продажу (Kufar.by)";

  private final KufarApiClient kufarApiClient;
  private final KufarProperties properties;

  public KufarRoomSaleConnector(KufarApiClient kufarApiClient, KufarProperties properties) {
    this.kufarApiClient = kufarApiClient;
    this.properties = properties;
  }

  @Override
  public String getSourceId() {
    return properties.roomSale().sourceId();
  }

  @Override
  public String getSupportedRegionCode() {
    return properties.roomSale().regionCode();
  }

  /**
   * Fetches room sale listings from Kufar.
   *
   * @return list of raw listings, never null, may be empty on source error
   */
  @Override
  @RateLimiter(name = "connector-kufar")
  @CircuitBreaker(name = "connector-kufar")
  @Retry(name = "connector-kufar", fallbackMethod = "fetchFallback")
  public List<RawListing> fetch() {
    return kufarApiClient.fetchAll(properties.roomSale(), DEAL_TYPE_SELL, PROPERTY_TYPE_ROOM, FALLBACK_TITLE);
  }

  /**
   * Fetches room sale listings published at or after the given timestamp.
   *
   * @param since lower-bound timestamp (exclusive)
   * @return list of recently published listings, never null
   */
  @RateLimiter(name = "connector-kufar")
  @CircuitBreaker(name = "connector-kufar")
  @Retry(name = "connector-kufar", fallbackMethod = "fetchDeltaFallback")
  public List<RawListing> fetchDelta(Instant since) {
    log.info("Delta fetch started: source={}, since={}", properties.roomSale().sourceId(), since);
    List<RawListing> result = kufarApiClient.fetchDelta(
        properties.roomSale(), since, DEAL_TYPE_SELL, PROPERTY_TYPE_ROOM, FALLBACK_TITLE);
    log.info("Delta fetch completed: source={}, fetched={}", properties.roomSale().sourceId(), result.size());
    return result;
  }

  List<RawListing> fetchFallback(Exception e) {
    log.error("All retry attempts exhausted for KufarRoomSale fetch: source={}", properties.roomSale().sourceId(), e);
    return List.of();
  }

  List<RawListing> fetchDeltaFallback(Instant since, Exception e) {
    log.error("All retry attempts exhausted for KufarRoomSale delta fetch: source={}, since={}",
        properties.roomSale().sourceId(), since, e);
    return List.of();
  }
}
