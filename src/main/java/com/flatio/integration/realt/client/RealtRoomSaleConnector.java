package com.flatio.integration.realt.client;

import com.flatio.integration.core.ConnectorTransientException;
import com.flatio.integration.core.ListingConnector;
import com.flatio.integration.core.RawListing;
import com.flatio.integration.realt.config.RealtRoomSaleProperties;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Connector for fetching room sale listings from the realt.by website.
 *
 * <p>Fetches SSR HTML from realt.by and delegates listing extraction to {@link RealtHtmlParser}.
 * All extracted listings have {@code dealType = SELL} and {@code propertyType = ROOM}.
 *
 * <p>Shares the {@code connector-realt-sale} Resilience4j rate limiter and circuit breaker with
 * {@link RealtSaleConnector} and {@link RealtHouseSaleConnector} — all target realt.by.
 */
@Service
@Slf4j
public class RealtRoomSaleConnector implements ListingConnector {

  private static final long DEFAULT_RETRY_AFTER_SECONDS = 5L;
  private static final long MAX_RETRY_AFTER_SECONDS = 60L;
  private static final int MAX_PAGES = 100;

  private static final String DEAL_TYPE_SELL = "SELL";
  private static final String PROPERTY_TYPE_ROOM = "ROOM";
  private static final String FALLBACK_TITLE = "Комната на Realt.by";
  private static final String NEXT_PAGE_SELECTOR = "a[data-testid='nextBtn']";

  private final RestClient restClient;
  private final RealtRoomSaleProperties properties;
  private final RealtHtmlParser htmlParser;
  private final RealtPageContext pageContext;

  public RealtRoomSaleConnector(@Qualifier("realtSaleRestClient") RestClient restClient,
      RealtRoomSaleProperties properties,
      RealtHtmlParser htmlParser) {
    this.restClient = restClient;
    this.properties = properties;
    this.htmlParser = htmlParser;
    this.pageContext = new RealtPageContext(
        properties.baseUrl(), properties.objectPathPrefix(), properties.sourceId(),
        DEAL_TYPE_SELL, PROPERTY_TYPE_ROOM, FALLBACK_TITLE
    );
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
   * Fetches all current room sale listings from realt.by by paginating through all pages.
   *
   * <p>Rate-limited, circuit-broken, and retried via Resilience4j; on exhausted retries
   * {@link #fetchFallback} returns an empty list.
   *
   * @return list of raw listings, never null, may be empty on source error
   */
  @Override
  @RateLimiter(name = "connector-realt-sale")
  @CircuitBreaker(name = "connector-realt-sale")
  @Retry(name = "connector-realt-sale", fallbackMethod = "fetchFallback")
  public List<RawListing> fetch() {
    return fetchAllInternal();
  }

  /**
   * Fetches room sale listings published at or after the given timestamp (delta sync).
   *
   * @param since lower-bound timestamp; listings before this value stop further pagination
   * @return list of recently published listings, never null, may be empty
   */
  @RateLimiter(name = "connector-realt-sale")
  @CircuitBreaker(name = "connector-realt-sale")
  @Retry(name = "connector-realt-sale", fallbackMethod = "fetchDeltaFallback")
  public List<RawListing> fetchDelta(Instant since) {
    log.info("Delta fetch started: source={}, since={}", properties.sourceId(), since);
    List<RawListing> result = new ArrayList<>();
    int currentPage = 1;
    boolean done = false;
    try {
      while (!done && currentPage <= MAX_PAGES) {
        String html = fetchPage(currentPage);
        if (html == null || html.isBlank()) {
          break;
        }
        Document doc = Jsoup.parse(html, properties.baseUrl());
        List<RawListing> pageListings = htmlParser.parseListings(doc, pageContext);
        if (pageListings.isEmpty()) {
          break;
        }
        for (RawListing listing : pageListings) {
          if (listing.publishedAt() != null && listing.publishedAt().isBefore(since)) {
            done = true;
            break;
          }
          result.add(listing);
        }
        if (!done) {
          boolean hasNextPage = doc.selectFirst(NEXT_PAGE_SELECTOR) != null;
          if (!hasNextPage) {
            break;
          }
        }
        currentPage++;
      }
    } catch (HttpClientErrorException.TooManyRequests e) {
      handleRateLimit(e);
    } catch (HttpClientErrorException e) {
      log.error("Non-retryable HTTP error during RealtRoomSale delta fetch: status={}, page={}, source={}",
          e.getStatusCode(), currentPage, properties.sourceId(), e);
    }
    log.info("Delta fetch completed: source={}, fetched={}", properties.sourceId(), result.size());
    return result;
  }

  // Package-private: Resilience4j AOP proxy requires fallback to be accessible from the same package.
  List<RawListing> fetchFallback(Exception e) {
    log.error("All retry attempts exhausted for RealtRoomSale fetch: source={}", properties.sourceId(), e);
    return List.of();
  }

  // Package-private: Resilience4j AOP proxy requires fallback to be accessible from the same package.
  List<RawListing> fetchDeltaFallback(Instant since, Exception e) {
    log.error("All retry attempts exhausted for RealtRoomSale delta fetch: source={}, since={}", properties.sourceId(), since, e);
    return List.of();
  }

  private List<RawListing> fetchAllInternal() {
    log.info("Full fetch started: source={}", properties.sourceId());
    List<RawListing> result = new ArrayList<>();
    int currentPage = 1;
    boolean hasNextPage = true;
    try {
      while (hasNextPage && currentPage <= MAX_PAGES) {
        String html = fetchPage(currentPage);
        if (html == null || html.isBlank()) {
          break;
        }
        Document doc = Jsoup.parse(html, properties.baseUrl());
        List<RawListing> pageListings = htmlParser.parseListings(doc, pageContext);
        if (pageListings.isEmpty()) {
          break;
        }
        result.addAll(pageListings);
        hasNextPage = doc.selectFirst(NEXT_PAGE_SELECTOR) != null;
        currentPage++;
      }
    } catch (HttpClientErrorException.TooManyRequests e) {
      handleRateLimit(e);
    } catch (HttpClientErrorException e) {
      log.error("Non-retryable HTTP error fetching realt.by room sale: status={}, page={}, source={}",
          e.getStatusCode(), currentPage, properties.sourceId(), e);
    }
    log.info("Full fetch completed: source={}, fetched={}", properties.sourceId(), result.size());
    return result;
  }

  private String fetchPage(int pageNumber) {
    return restClient.get()
        .uri(uriBuilder -> uriBuilder
            .path(properties.listingsPath())
            .queryParam("page", pageNumber)
            .build())
        .retrieve()
        .body(String.class);
  }

  private void handleRateLimit(HttpClientErrorException.TooManyRequests e) {
    long retryAfterSeconds = parseRetryAfterSeconds(e.getResponseHeaders());
    log.warn("Rate limited by realt.by (429): source={}, retryAfter={}s — Resilience4j will back off",
        properties.sourceId(), retryAfterSeconds);
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
}
