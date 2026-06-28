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
 * Connector for fetching apartment rental listings from the Kufar JSON API.
 *
 * <p>All listings have {@code dealType = RENT} and {@code propertyType = APARTMENT}.
 * Pagination, HTTP handling, and mapping are delegated to {@link KufarApiClient}.
 * Rate limiting, circuit breaker, and retry are applied via the shared
 * {@code connector-kufar} Resilience4j configuration.
 */
@Service
@Slf4j
public class KufarApartmentRentConnector implements ListingConnector {

  private static final String DEAL_TYPE_RENT = "RENT";
  private static final String PROPERTY_TYPE_APARTMENT = "APARTMENT";
  private static final String FALLBACK_TITLE = "Квартира в аренду (Kufar.by)";

  private final KufarApiClient kufarApiClient;
  private final KufarProperties properties;

  public KufarApartmentRentConnector(KufarApiClient kufarApiClient, KufarProperties properties) {
    this.kufarApiClient = kufarApiClient;
    this.properties = properties;
  }

  @Override
  public String getSourceId() {
    return properties.apartmentRent().sourceId();
  }

  @Override
  public String getSupportedRegionCode() {
    return properties.apartmentRent().regionCode();
  }

  /**
   * Fetches apartment rental listings from Kufar.
   *
   * @return list of raw listings, never null, may be empty on source error
   */
  @Override
  @RateLimiter(name = "connector-kufar")
  @CircuitBreaker(name = "connector-kufar")
  @Retry(name = "connector-kufar", fallbackMethod = "fetchFallback")
  public List<RawListing> fetch() {
    return kufarApiClient.fetchAll(properties.apartmentRent(), DEAL_TYPE_RENT, PROPERTY_TYPE_APARTMENT, FALLBACK_TITLE);
  }

  /**
   * Fetches apartment rental listings published at or after the given timestamp.
   *
   * @param since lower-bound timestamp (exclusive)
   * @return list of recently published listings, never null
   */
  @RateLimiter(name = "connector-kufar")
  @CircuitBreaker(name = "connector-kufar")
  @Retry(name = "connector-kufar", fallbackMethod = "fetchDeltaFallback")
  public List<RawListing> fetchDelta(Instant since) {
    log.info("Delta fetch started: source={}, since={}", properties.apartmentRent().sourceId(), since);
    List<RawListing> result = kufarApiClient.fetchDelta(
        properties.apartmentRent(), since, DEAL_TYPE_RENT, PROPERTY_TYPE_APARTMENT, FALLBACK_TITLE);
    log.info("Delta fetch completed: source={}, fetched={}", properties.apartmentRent().sourceId(), result.size());
    return result;
  }

  // Package-private: Resilience4j AOP proxy requires fallback methods accessible from the same package.
  List<RawListing> fetchFallback(Exception e) {
    log.error("All retry attempts exhausted for KufarApartmentRent fetch: source={}", properties.apartmentRent().sourceId(), e);
    return List.of();
  }

  List<RawListing> fetchDeltaFallback(Instant since, Exception e) {
    log.error("All retry attempts exhausted for KufarApartmentRent delta fetch: source={}, since={}",
        properties.apartmentRent().sourceId(), since, e);
    return List.of();
  }
}
