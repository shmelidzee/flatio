package com.flatio.integration.realt.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Connector for fetching apartment rental listings from the realt.by website.
 *
 * <p>Fetches SSR HTML from realt.by (Next.js) and extracts listing data from the embedded
 * {@code __NEXT_DATA__} JSON, which carries the complete listing payload including images and
 * structured fields. HTML is only used to detect whether a next page link is present (pagination).
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

  // Selects the embedded Next.js JSON script; all listing fields are read from it.
  private static final String NEXT_DATA_SELECTOR = "script#__NEXT_DATA__";
  // JsonPointer path to the listing objects array inside __NEXT_DATA__.
  private static final String JSON_OBJECTS_PATH = "/props/pageProps/objects";
  // Present on pages that have a following page.
  private static final String NEXT_PAGE_SELECTOR = "a[data-testid='nextBtn']";

  private final RestClient restClient;
  private final RealtProperties properties;
  private final ObjectMapper objectMapper;

  public RealtConnector(@Qualifier("realtRestClient") RestClient restClient,
      RealtProperties properties,
      ObjectMapper objectMapper) {
    this.restClient = restClient;
    this.properties = properties;
    this.objectMapper = objectMapper;
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
   * <p>Each page's listing data is extracted from the embedded {@code __NEXT_DATA__} JSON.
   * Pagination stops when a page returns no listing objects or the "next" button is absent.
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
        List<RawListing> pageListings = parseListingsFromJson(doc);
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
   * Extracts listing objects from the {@code __NEXT_DATA__} JSON embedded in the page.
   *
   * @param doc parsed HTML document of a listing page
   * @return list of parsed listings, empty on missing script or parse error
   */
  private List<RawListing> parseListingsFromJson(Document doc) {
    Element scriptEl = doc.selectFirst(NEXT_DATA_SELECTOR);
    if (scriptEl == null) {
      log.warn("__NEXT_DATA__ script not found on page: source={}", properties.sourceId());
      return List.of();
    }
    try {
      JsonNode objects = objectMapper.readTree(scriptEl.data()).at(JSON_OBJECTS_PATH);
      if (!objects.isArray() || objects.isEmpty()) {
        return List.of();
      }
      List<RawListing> result = new ArrayList<>();
      for (JsonNode obj : objects) {
        safeAddFromJson(result, obj);
      }
      return result;
    } catch (JsonProcessingException e) {
      log.error("Failed to parse __NEXT_DATA__ JSON: source={}", properties.sourceId(), e);
      return List.of();
    }
  }

  private void safeAddFromJson(List<RawListing> result, JsonNode obj) {
    String hint = obj.path("code").asText("unknown");
    try {
      result.add(toRawListing(obj));
    } catch (Exception e) {
      log.warn("Skipping broken Realt listing: hint={}, error={}", hint, e.getMessage());
    }
  }

  private RawListing toRawListing(JsonNode obj) {
    String externalId = extractExternalId(obj);
    BigDecimal price = extractPrice(obj, externalId);
    String title = extractTitle(obj);
    String address = Optional.ofNullable(obj.path("address").textValue())
        .filter(t -> !t.isBlank())
        .orElse(null);
    String city = obj.path("townName").textValue();
    Instant publishedAt = parseInstant(obj.path("createdAt").textValue(), externalId);
    Boolean isOwner = obj.path("companyUuid").isNull();

    return new RawListing(
        externalId, title, null,
        DEAL_TYPE_RENT, PROPERTY_TYPE_APARTMENT,
        price, "BYN", null,
        jsonIntOrNull(obj, "rooms"),
        jsonIntOrNull(obj, "storey"),
        jsonIntOrNull(obj, "storeys"),
        jsonBigDecimalOrNull(obj, "areaTotal"),
        address, null, null, city,
        properties.baseUrl() + properties.objectPathPrefix() + externalId + "/",
        publishedAt,
        extractPhotos(obj),
        isOwner, null
    );
  }

  private String extractExternalId(JsonNode obj) {
    int code = obj.path("code").asInt(0);
    if (code == 0) {
      throw new IllegalArgumentException("Missing or invalid code field");
    }
    return String.valueOf(code);
  }

  private BigDecimal extractPrice(JsonNode obj, String externalId) {
    long priceRaw = obj.path("price").asLong(0L);
    if (priceRaw <= 0) {
      throw new IllegalArgumentException("Missing or zero price for listing code=" + externalId);
    }
    return BigDecimal.valueOf(priceRaw);
  }

  private String extractTitle(JsonNode obj) {
    String title = Optional.ofNullable(obj.path("title").textValue())
        .filter(t -> !t.isBlank())
        .orElseGet(() -> Optional.ofNullable(obj.path("headline").textValue())
            .filter(t -> !t.isBlank())
            .orElse(FALLBACK_TITLE));
    return title.strip();
  }

  private List<String> extractPhotos(JsonNode obj) {
    JsonNode imagesNode = obj.path("images");
    if (!imagesNode.isArray()) {
      return List.of();
    }
    List<String> photos = new ArrayList<>();
    for (JsonNode img : imagesNode) {
      String url = img.asText();
      if (!url.isBlank()) {
        photos.add(url);
      }
    }
    return List.copyOf(photos);
  }

  private Integer jsonIntOrNull(JsonNode obj, String fieldName) {
    JsonNode node = obj.path(fieldName);
    return (node.isNull() || node.isMissingNode()) ? null : node.asInt();
  }

  private BigDecimal jsonBigDecimalOrNull(JsonNode obj, String fieldName) {
    JsonNode node = obj.path(fieldName);
    return node.isNumber() ? node.decimalValue() : null;
  }

  private Instant parseInstant(String dateTimeStr, String externalId) {
    if (dateTimeStr == null || dateTimeStr.isBlank()) {
      return null;
    }
    try {
      return OffsetDateTime.parse(dateTimeStr).toInstant();
    } catch (DateTimeParseException e) {
      log.debug("Cannot parse createdAt={} for listing code={}", dateTimeStr, externalId);
      return null;
    }
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
