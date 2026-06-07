package com.flatio.connector.onliner;

import com.flatio.connector.core.ListingConnector;
import com.flatio.connector.core.RawListing;
import com.flatio.connector.onliner.dto.OnlinerApartment;
import com.flatio.connector.onliner.dto.OnlinerSearchResponse;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Connector for fetching apartment listings from the Onliner API.
 *
 * <p>Implements rate limiting (1 request/second) and retry with exponential backoff
 * via Resilience4j. Each listing is parsed in isolation — a broken entry does not
 * abort the full fetch. No raw HTML or binary content is stored.
 */
@Service
@Slf4j
public class OnlinerConnector implements ListingConnector {

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
   * <p>Rate-limited to 1 request/second. Retries up to 3 times with exponential backoff
   * on transient failures. After all retries are exhausted, {@link #fetchFallback} is
   * invoked and returns an empty list.
   *
   * @return list of raw listings, never null
   */
  @Override
  @RateLimiter(name = "connector-onliner")
  @Retry(name = "connector-onliner", fallbackMethod = "fetchFallback")
  public List<RawListing> fetch() {
    log.info("Fetching listings from Onliner: source={}, region={}", properties.sourceId(), properties.regionCode());
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
  }

  /**
   * Fallback invoked by Resilience4j after all retry attempts are exhausted.
   *
   * @param e the exception that caused all retries to fail
   * @return empty list — never null, per {@link com.flatio.connector.core.ListingConnector} contract
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
    BigDecimal price = apartment.price() != null
        ? new BigDecimal(apartment.price().amount())
        : null;
    String currency = apartment.price() != null ? apartment.price().currency() : null;
    BigDecimal lat = apartment.location() != null ? apartment.location().latitude() : null;
    BigDecimal lon = apartment.location() != null ? apartment.location().longitude() : null;
    String address = apartment.location() != null ? apartment.location().address() : null;
    BigDecimal area = apartment.area() != null ? apartment.area().total() : null;
    List<String> photos = apartment.photo() != null ? List.of(apartment.photo()) : List.of();
    Instant publishedAt = parseInstant(apartment.createdAt());
    String title = buildTitle(apartment.roomsCount(), area, address);

    return new RawListing(
        String.valueOf(apartment.id()),
        title,
        null,
        apartment.dealType(),
        "APARTMENT",
        price,
        currency,
        apartment.roomsCount(),
        apartment.floor(),
        apartment.numberOfFloors(),
        area,
        address,
        lat,
        lon,
        null,
        apartment.url(),
        publishedAt,
        photos
    );
  }

  private String buildTitle(Integer rooms, BigDecimal area, String address) {
    StringBuilder title = new StringBuilder();
    if (rooms != null) {
      title.append(rooms).append("-комн.");
    }
    if (area != null) {
      if (!title.isEmpty()) {
        title.append(", ");
      }
      title.append(area).append(" м²");
    }
    if (address != null && !address.isBlank()) {
      if (!title.isEmpty()) {
        title.append(", ");
      }
      title.append(address);
    }
    return title.isEmpty() ? "Квартира на Onliner" : title.toString();
  }

  private Instant parseInstant(String dateTime) {
    if (dateTime == null) {
      return null;
    }
    try {
      return Instant.parse(dateTime);
    } catch (Exception e) {
      log.debug("Cannot parse Onliner datetime '{}': {}", dateTime, e.getMessage());
      return null;
    }
  }
}
