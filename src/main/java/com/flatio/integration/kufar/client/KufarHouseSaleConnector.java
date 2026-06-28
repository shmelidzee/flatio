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
 * Connector for fetching house/cottage sale listings from the Kufar JSON API.
 *
 * <p>All listings have {@code dealType = SELL} and {@code propertyType = HOUSE}.
 */
@Service
@Slf4j
public class KufarHouseSaleConnector implements ListingConnector {

  private static final String DEAL_TYPE_SELL = "SELL";
  private static final String PROPERTY_TYPE_HOUSE = "HOUSE";
  private static final String FALLBACK_TITLE = "Дом на продажу (Kufar.by)";

  private final KufarApiClient kufarApiClient;
  private final KufarProperties properties;

  public KufarHouseSaleConnector(KufarApiClient kufarApiClient, KufarProperties properties) {
    this.kufarApiClient = kufarApiClient;
    this.properties = properties;
  }

  @Override
  public String getSourceId() {
    return properties.houseSale().sourceId();
  }

  @Override
  public String getSupportedRegionCode() {
    return properties.houseSale().regionCode();
  }

  /**
   * Fetches house sale listings from Kufar.
   *
   * @return list of raw listings, never null, may be empty on source error
   */
  @Override
  @RateLimiter(name = "connector-kufar")
  @CircuitBreaker(name = "connector-kufar")
  @Retry(name = "connector-kufar", fallbackMethod = "fetchFallback")
  public List<RawListing> fetch() {
    return kufarApiClient.fetchAll(properties.houseSale(), DEAL_TYPE_SELL, PROPERTY_TYPE_HOUSE, FALLBACK_TITLE);
  }

  /**
   * Fetches all house sale listings by paginating through every available page.
   *
   * @return complete list of raw listings, never null
   */
  @RateLimiter(name = "connector-kufar")
  @CircuitBreaker(name = "connector-kufar")
  @Retry(name = "connector-kufar", fallbackMethod = "fetchAllFallback")
  public List<RawListing> fetchAll() {
    log.info("Full fetch started: source={}", properties.houseSale().sourceId());
    List<RawListing> result = kufarApiClient.fetchAll(
        properties.houseSale(), DEAL_TYPE_SELL, PROPERTY_TYPE_HOUSE, FALLBACK_TITLE);
    log.info("Full fetch completed: source={}, fetched={}", properties.houseSale().sourceId(), result.size());
    return result;
  }

  /**
   * Fetches house sale listings published at or after the given timestamp.
   *
   * @param since lower-bound timestamp (exclusive)
   * @return list of recently published listings, never null
   */
  @RateLimiter(name = "connector-kufar")
  @CircuitBreaker(name = "connector-kufar")
  @Retry(name = "connector-kufar", fallbackMethod = "fetchDeltaFallback")
  public List<RawListing> fetchDelta(Instant since) {
    log.info("Delta fetch started: source={}, since={}", properties.houseSale().sourceId(), since);
    List<RawListing> result = kufarApiClient.fetchDelta(
        properties.houseSale(), since, DEAL_TYPE_SELL, PROPERTY_TYPE_HOUSE, FALLBACK_TITLE);
    log.info("Delta fetch completed: source={}, fetched={}", properties.houseSale().sourceId(), result.size());
    return result;
  }

  List<RawListing> fetchFallback(Exception e) {
    log.error("All retry attempts exhausted for KufarHouseSale fetch: source={}", properties.houseSale().sourceId(), e);
    return List.of();
  }

  List<RawListing> fetchAllFallback(Exception e) {
    log.error("All retry attempts exhausted for KufarHouseSale full fetch: source={}", properties.houseSale().sourceId(), e);
    return List.of();
  }

  List<RawListing> fetchDeltaFallback(Instant since, Exception e) {
    log.error("All retry attempts exhausted for KufarHouseSale delta fetch: source={}, since={}",
        properties.houseSale().sourceId(), since, e);
    return List.of();
  }
}
