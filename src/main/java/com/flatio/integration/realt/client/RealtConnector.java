package com.flatio.integration.realt.client;

import com.flatio.integration.core.ConnectorTransientException;
import com.flatio.integration.core.ListingConnector;
import com.flatio.integration.core.RawListing;
import com.flatio.integration.realt.config.RealtProperties;
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
 * Connector for fetching apartment rental listings from the realt.by website.
 *
 * <p>Fetches SSR HTML from realt.by (Next.js) and delegates listing extraction to
 * {@link RealtHtmlParser}. Pagination is driven by the presence of the
 * {@code data-testid="nextBtn"} anchor in the HTML.
 *
 * <p>Rate limiting (1 req/2 s), circuit breaker (opens after 5 failures, stays open 60 s),
 * and retry with exponential backoff (3 attempts: 2 s → 4 s → 8 s) are applied via Resilience4j.
 * On exhausted retries {@link #fetchFallback} returns an empty list.
 */
@Service
@Slf4j
public class RealtConnector implements ListingConnector {

  private static final long DEFAULT_RETRY_AFTER_SECONDS = 5L;
  private static final long MAX_RETRY_AFTER_SECONDS = 60L;
  private static final int MAX_PAGES = 100;

  private static final String DEAL_TYPE_RENT = "RENT";
  private static final String PROPERTY_TYPE_APARTMENT = "APARTMENT";
  private static final String FALLBACK_TITLE = "Квартира на Realt.by";
  // Present on pages that have a following page.
  private static final String NEXT_PAGE_SELECTOR = "a[data-testid='nextBtn']";

  private final RestClient restClient;
  private final RealtProperties properties;
  private final RealtHtmlParser htmlParser;
  private final RealtPageContext pageContext;

  public RealtConnector(@Qualifier("realtRestClient") RestClient restClient,
      RealtProperties properties,
      RealtHtmlParser htmlParser) {
    this.restClient = restClient;
    this.properties = properties;
    this.htmlParser = htmlParser;
    this.pageContext = new RealtPageContext(
        properties.baseUrl(), properties.objectPathPrefix(), properties.sourceId(),
        DEAL_TYPE_RENT, PROPERTY_TYPE_APARTMENT, FALLBACK_TITLE
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
   * Fetches all current rental listings from realt.by by paginating through all available pages.
   *
   * <p>Rate-limited, circuit-broken, and retried via Resilience4j; on exhausted retries
   * {@link #fetchFallback} returns an empty list.
   *
   * @return list of raw listings, never null, may be empty on source error
   */
  @Override
  @RateLimiter(name = "connector-realt")
  @CircuitBreaker(name = "connector-realt")
  @Retry(name = "connector-realt", fallbackMethod = "fetchFallback")
  public List<RawListing> fetch() {
    return fetchAllInternal();
  }

  /**
   * Fetches listings published at or after the given timestamp (delta sync).
   *
   * <p>Realt.by pages are ordered by {@code createdAt} descending. Pagination stops as soon
   * as a listing's {@code publishedAt} is strictly before {@code since}.
   *
   * @param since lower-bound timestamp; listings before this value stop further pagination
   * @return list of recently published listings, never null, may be empty
   */
  @RateLimiter(name = "connector-realt")
  @CircuitBreaker(name = "connector-realt")
  @Retry(name = "connector-realt", fallbackMethod = "fetchDeltaFallback")
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
      handleDeltaFetchRateLimit(result, e);
    } catch (HttpClientErrorException e) {
      log.error("Non-retryable HTTP error during Realt delta fetch: status={}, page={}, source={}",
          e.getStatusCode(), currentPage, properties.sourceId(), e);
    }
    log.info("Delta fetch completed: source={}, fetched={}", properties.sourceId(), result.size());
    return result;
  }

  // Package-private: Resilience4j AOP proxy requires fallback to be accessible from the same package.
  List<RawListing> fetchFallback(Exception e) {
    log.error("All retry attempts exhausted for Realt fetch: source={}", properties.sourceId(), e);
    return List.of();
  }

  // Package-private: Resilience4j AOP proxy requires fallback to be accessible from the same package.
  List<RawListing> fetchDeltaFallback(Instant since, Exception e) {
    log.error("All retry attempts exhausted for Realt delta fetch: source={}, since={}", properties.sourceId(), since, e);
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
      handleFullFetchRateLimit(e);
    } catch (HttpClientErrorException e) {
      log.error("Non-retryable HTTP error fetching realt.by: status={}, page={}, source={}",
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

  /**
   * Handles a 429 response encountered mid-pagination during a full fetch.
   *
   * <p>Unlike {@link #handleDeltaFetchRateLimit}, this always rethrows — a full fetch's result
   * feeds {@code applyMissedSyncPenalty}, which treats every listing absent from the result as
   * gone and deactivates it. Keeping a page-range-truncated partial result here would silently
   * mass-deactivate every listing on the pages not yet reached, the same false-mass-deactivation
   * risk closed for Kufar in issue #366. Rethrowing lets Resilience4j retry the whole fetch from
   * page 1, and its fallback returns an empty list on exhausted retries, which the full-sync job
   * already treats as "skip deactivation to avoid data loss".
   *
   * @param e the 429 response caught by the caller
   */
  private void handleFullFetchRateLimit(HttpClientErrorException.TooManyRequests e) {
    long retryAfterSeconds = parseRetryAfterSeconds(e.getResponseHeaders());
    log.warn("Rate limited by realt.by (429) during full fetch: source={}, retryAfter={}s — Resilience4j will back off",
        properties.sourceId(), retryAfterSeconds);
    throw new ConnectorTransientException("Rate limited: source=" + properties.sourceId(), e);
  }

  /**
   * Handles a 429 response encountered mid-pagination during a delta fetch.
   *
   * <p>Delta results are only used to ingest new/updated listings, never to deactivate absent
   * ones, so a partial result is safe to keep instead of discarding already-fetched pages.
   *
   * @param result the listings collected so far in this fetch, never null
   * @param e      the 429 response caught by the caller
   */
  private void handleDeltaFetchRateLimit(List<RawListing> result, HttpClientErrorException.TooManyRequests e) {
    long retryAfterSeconds = parseRetryAfterSeconds(e.getResponseHeaders());
    if (result.isEmpty()) {
      log.warn("Rate limited by realt.by (429): source={}, retryAfter={}s — Resilience4j will back off",
          properties.sourceId(), retryAfterSeconds);
      throw new ConnectorTransientException("Rate limited: source=" + properties.sourceId(), e);
    }
    log.warn("Rate limited by realt.by (429) after collecting {} listing(s) mid-pagination — "
        + "returning partial result instead of retrying from page 1: source={}",
        result.size(), properties.sourceId());
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
