package com.flatio.integration.realt.client;

import com.flatio.integration.core.ConnectorTransientException;
import com.flatio.integration.core.ListingConnector;
import com.flatio.integration.core.RawListing;
import com.flatio.integration.realt.config.RealtProperties;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Connector for fetching apartment rental listings from the realt.by website.
 *
 * <p>Fetches and parses HTML listing pages from realt.by. Listings are priced in USD.
 * Each listing card is parsed in isolation — a broken card does not abort the full fetch.
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

  private static final String CARD_SELECTOR = "article.classified";
  private static final String EXTERNAL_ID_ATTR = "data-classified-id";
  private static final String TITLE_LINK_SELECTOR = "h2.classified__title a";
  private static final String ADDRESS_SELECTOR = "p.classified__address";
  private static final String PRICE_AMOUNT_SELECTOR = "span.price__amount";
  private static final String PHOTO_SELECTOR = "img.classified__image";
  private static final String NEXT_PAGE_SELECTOR = "a[rel=next]";

  private final RestClient restClient;
  private final RealtProperties properties;

  public RealtConnector(@Qualifier("realtRestClient") RestClient restClient,
      RealtProperties properties) {
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
   * Fetches all current rental listings from realt.by by paginating through all available pages.
   *
   * <p>Pagination stops when the current page contains no listing cards or has no "next" link.
   * Rate-limited, circuit-broken, and retried via Resilience4j; on exhausted retries
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

  // Package-private: Resilience4j AOP proxy requires fallback to be accessible from the same package.
  List<RawListing> fetchFallback(Exception e) {
    log.error("All retry attempts exhausted for Realt fetch: source={}", properties.sourceId(), e);
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
        Elements cards = doc.select(CARD_SELECTOR);
        if (cards.isEmpty()) {
          break;
        }
        result.addAll(parseCards(cards));
        hasNextPage = doc.selectFirst(NEXT_PAGE_SELECTOR) != null;
        currentPage++;
      }
    } catch (HttpClientErrorException.TooManyRequests e) {
      handleRateLimit(e);
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

  private List<RawListing> parseCards(Elements cards) {
    List<RawListing> result = new ArrayList<>();
    for (Element card : cards) {
      safeAdd(result, card);
    }
    return result;
  }

  private void safeAdd(List<RawListing> result, Element card) {
    String externalId = card.attr(EXTERNAL_ID_ATTR);
    try {
      result.add(toRawListing(card));
    } catch (Exception e) {
      log.warn("Skipping broken Realt listing: id={}, error={}", externalId, e.getMessage());
    }
  }

  private RawListing toRawListing(Element card) {
    String externalId = card.attr(EXTERNAL_ID_ATTR);
    if (externalId.isBlank()) {
      throw new IllegalArgumentException("Missing external ID in listing card");
    }

    Element titleLink = card.selectFirst(TITLE_LINK_SELECTOR);
    if (titleLink == null) {
      throw new IllegalArgumentException("Missing title element for listing id=" + externalId);
    }
    String title = titleLink.text().isBlank() ? FALLBACK_TITLE : titleLink.text();
    String sourceUrl = properties.baseUrl() + titleLink.attr("href");

    String priceText = Optional.ofNullable(card.selectFirst(PRICE_AMOUNT_SELECTOR))
        .map(el -> el.text().replaceAll("[^\\d.]", ""))
        .orElse("");
    if (priceText.isBlank()) {
      throw new IllegalArgumentException("Missing price for listing id=" + externalId);
    }
    BigDecimal price = new BigDecimal(priceText);

    String address = Optional.ofNullable(card.selectFirst(ADDRESS_SELECTOR))
        .map(Element::text)
        .orElse(null);

    String photoSrc = Optional.ofNullable(card.selectFirst(PHOTO_SELECTOR))
        .map(el -> el.attr("src"))
        .filter(src -> !src.isBlank())
        .orElse(null);
    List<String> photos = photoSrc != null ? List.of(photoSrc) : List.of();

    return new RawListing(
        externalId,
        title,
        null,
        DEAL_TYPE_RENT,
        PROPERTY_TYPE_APARTMENT,
        price,
        "USD",
        price,
        null,
        null,
        null,
        null,
        address,
        null,
        null,
        null,
        sourceUrl,
        null,
        photos,
        null,
        null
    );
  }

  private void handleRateLimit(HttpClientErrorException.TooManyRequests e) {
    long retryAfterSeconds = parseRetryAfterSeconds(e.getResponseHeaders());
    log.warn("Rate limited by realt.by (429): source={}, retryAfterSeconds={}",
        properties.sourceId(), retryAfterSeconds);
    sleepQuietly(retryAfterSeconds * 1_000L);
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

  private void sleepQuietly(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
