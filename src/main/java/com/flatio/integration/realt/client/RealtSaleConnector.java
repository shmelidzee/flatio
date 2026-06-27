package com.flatio.integration.realt.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flatio.integration.core.ConnectorTransientException;
import com.flatio.integration.core.ListingConnector;
import com.flatio.integration.core.RawListing;
import com.flatio.integration.realt.config.RealtSaleProperties;
import com.flatio.service.CurrencyRateService;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Connector for fetching apartment sale listings from the realt.by website.
 *
 * <p>Fetches SSR HTML from realt.by (Next.js) and extracts listing data from the embedded
 * {@code __NEXT_DATA__} JSON, which carries the complete listing payload including images and
 * structured fields. HTML is only used to detect whether a next page link is present (pagination).
 *
 * <p>All extracted listings have {@code dealType = SELL}.
 *
 * <p>Rate limiting (1 req/2 s), circuit breaker (opens after 5 failures, stays open 60 s),
 * and retry with exponential backoff (3 attempts: 2 s → 4 s → 8 s) are applied via Resilience4j.
 * On exhausted retries {@link #fetchFallback} returns an empty list.
 */
@Service
@Slf4j
public class RealtSaleConnector implements ListingConnector {

  private static final long DEFAULT_RETRY_AFTER_SECONDS = 5L;
  private static final long MAX_RETRY_AFTER_SECONDS = 60L;
  private static final int MAX_PAGES = 100;

  private static final String DEAL_TYPE_SELL = "SELL";
  private static final String PROPERTY_TYPE_APARTMENT = "APARTMENT";
  private static final String FALLBACK_TITLE = "Квартира на Realt.by";
  private static final String CURRENCY_USD = "USD";
  private static final String CURRENCY_BYN = "BYN";
  // ISO 4217 numeric code for BYN (Belarusian Ruble); realt.by uses 840 (USD) for most listings.
  private static final int ISO_CURRENCY_BYN = 933;

  // Guard against OOM if a response embeds unexpectedly large payloads.
  private static final int MAX_NEXT_DATA_SIZE = 5 * 1_024 * 1_024;

  // Selects the embedded Next.js JSON script; all listing fields are read from it.
  private static final String NEXT_DATA_SELECTOR = "script#__NEXT_DATA__";
  // JsonPointer path to the listing objects array inside __NEXT_DATA__.
  private static final String JSON_OBJECTS_PATH = "/props/pageProps/objects";
  // Present on pages that have a following page.
  private static final String NEXT_PAGE_SELECTOR = "a[data-testid='nextBtn']";

  private final RestClient restClient;
  private final RealtSaleProperties properties;
  private final ObjectMapper objectMapper;
  private final CurrencyRateService currencyRateService;

  public RealtSaleConnector(@Qualifier("realtSaleRestClient") RestClient restClient,
      RealtSaleProperties properties,
      ObjectMapper objectMapper,
      CurrencyRateService currencyRateService) {
    this.restClient = restClient;
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.currencyRateService = currencyRateService;
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
   * Fetches all current sale listings from realt.by by paginating through all available pages.
   *
   * <p>Each page's listing data is extracted from the embedded {@code __NEXT_DATA__} JSON.
   * Pagination stops when a page returns no listing objects or the "next" button is absent.
   * Rate-limited, circuit-broken, and retried via Resilience4j; on exhausted retries
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
   * Fetches sale listings published at or after the given timestamp (delta sync).
   *
   * <p>Realt.by pages are ordered by {@code createdAt} descending (newest first). Pagination
   * stops as soon as a listing's {@code publishedAt} is strictly before {@code since}, so only
   * new and recently updated listings are returned. Rate-limited, circuit-broken, and retried
   * via Resilience4j; on exhausted retries {@link #fetchDeltaFallback} returns an empty list.
   *
   * @param since lower-bound timestamp; listings with {@code publishedAt} before this value
   *              are skipped and stop further pagination
   * @return list of recently published sale listings, never null, may be empty
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
        List<RawListing> pageListings = parseListingsFromJson(doc);
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
      log.error("Non-retryable HTTP error during RealtSale delta fetch: status={}, page={}, source={}",
          e.getStatusCode(), currentPage, properties.sourceId(), e);
    }
    log.info("Delta fetch completed: source={}, fetched={}", properties.sourceId(), result.size());
    return result;
  }

  // Package-private: Resilience4j AOP proxy requires fallback to be accessible from the same package.
  List<RawListing> fetchFallback(Exception e) {
    log.error("All retry attempts exhausted for RealtSale fetch: source={}", properties.sourceId(), e);
    return List.of();
  }

  // Package-private: Resilience4j AOP proxy requires fallback to be accessible from the same package.
  List<RawListing> fetchDeltaFallback(Instant since, Exception e) {
    log.error("All retry attempts exhausted for RealtSale delta fetch: source={}, since={}",
        properties.sourceId(), since, e);
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
      log.error("Non-retryable HTTP error fetching realt.by sale: status={}, page={}, source={}",
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
   * @param doc parsed HTML document of a sale listing page
   * @return list of parsed listings, empty on missing script or parse error
   */
  private List<RawListing> parseListingsFromJson(Document doc) {
    Element scriptEl = doc.selectFirst(NEXT_DATA_SELECTOR);
    if (scriptEl == null) {
      log.warn("__NEXT_DATA__ script not found on page: source={}", properties.sourceId());
      return List.of();
    }
    String data = scriptEl.data();
    if (data.length() > MAX_NEXT_DATA_SIZE) {
      log.error("__NEXT_DATA__ exceeds size limit: size={}, source={}", data.length(), properties.sourceId());
      return List.of();
    }
    try {
      JsonNode objects = objectMapper.readTree(data).at(JSON_OBJECTS_PATH);
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
    String hint = obj.path("code").asText("unknown").replaceAll("[\r\n\t]", "_");
    try {
      result.add(toRawListing(obj));
    } catch (Exception e) {
      log.warn("Skipping broken RealtSale listing: hint={}, error={}", hint, e.getMessage());
    }
  }

  private RawListing toRawListing(JsonNode obj) {
    String externalId = extractExternalId(obj);
    BigDecimal price = extractPrice(obj, externalId);
    String title = extractTitle(obj);
    String currency = resolveCurrency(obj);
    // priceUsd is null: price is already in USD for Realt listings.
    BigDecimal priceUsd = null;
    BigDecimal priceByn = computePriceByn(price, currency, externalId);
    String address = Optional.ofNullable(obj.path("address").textValue())
        .filter(t -> !t.isBlank())
        .orElse(null);
    String city = obj.path("townName").textValue();
    Instant publishedAt = parseInstant(obj.path("createdAt").textValue(), externalId);
    Boolean isOwner = extractIsOwner(obj);

    return new RawListing(
        externalId, title, null,
        DEAL_TYPE_SELL, PROPERTY_TYPE_APARTMENT,
        price, currency, priceUsd, priceByn,
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

  private BigDecimal computePriceByn(BigDecimal price, String currency, String externalId) {
    if (!CURRENCY_USD.equals(currency)) {
      return null;
    }
    return currencyRateService.getUsdToByn()
        .map(rate -> price.multiply(rate).setScale(2, RoundingMode.HALF_UP))
        .orElseGet(() -> {
          log.debug("USD/BYN rate unavailable, priceByn not set: externalId={}", externalId);
          return null;
        });
  }

  private String resolveCurrency(JsonNode obj) {
    return obj.path("priceCurrency").asInt(0) == ISO_CURRENCY_BYN ? CURRENCY_BYN : CURRENCY_USD;
  }

  private Boolean extractIsOwner(JsonNode obj) {
    JsonNode companyNode = obj.path("companyUuid");
    if (companyNode.isMissingNode()) {
      return null;
    }
    return companyNode.isNull();
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
      if (isSafeImageUrl(url)) {
        photos.add(url);
      }
    }
    return List.copyOf(photos);
  }

  private boolean isSafeImageUrl(String url) {
    if (url == null || url.isBlank()) {
      return false;
    }
    try {
      URI uri = URI.create(url);
      return "https".equalsIgnoreCase(uri.getScheme())
          && uri.getHost() != null
          && !uri.getHost().isBlank();
    } catch (IllegalArgumentException e) {
      return false;
    }
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
      log.debug("Cannot parse createdAt={} for listing code={}",
          dateTimeStr.replaceAll("[\r\n\t]", "_"), externalId);
      return null;
    }
  }

  private void handleRateLimit(HttpClientErrorException.TooManyRequests e) {
    long retryAfterSeconds = parseRetryAfterSeconds(e.getResponseHeaders());
    log.warn("Rate limited by realt.by sale (429): source={}, retryAfter={}s — Resilience4j will back off",
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
