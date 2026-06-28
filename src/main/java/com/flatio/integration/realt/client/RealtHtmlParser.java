package com.flatio.integration.realt.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flatio.integration.core.RawListing;
import com.flatio.service.CurrencyRateService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

/**
 * Stateless HTML parser for realt.by listing pages.
 *
 * <p>Extracts listing data from the embedded {@code __NEXT_DATA__} Next.js JSON present on all
 * realt.by category pages. A {@link RealtPageContext} is passed per call so that one parser
 * instance serves all connectors (apartment rent/sale, room rent/sale, house sale).
 *
 * <p>Safe for concurrent use — no mutable state.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class RealtHtmlParser {

  private static final int MAX_NEXT_DATA_SIZE = 5 * 1_024 * 1_024;
  private static final String NEXT_DATA_SELECTOR = "script#__NEXT_DATA__";
  private static final String JSON_OBJECTS_PATH = "/props/pageProps/objects";
  private static final String CURRENCY_USD = "USD";
  private static final String CURRENCY_BYN = "BYN";
  // ISO 4217 numeric code for BYN (Belarusian Ruble); realt.by uses 840 (USD) for most listings.
  private static final int ISO_CURRENCY_BYN = 933;

  private final ObjectMapper objectMapper;
  private final CurrencyRateService currencyRateService;

  /**
   * Extracts listing objects from the {@code __NEXT_DATA__} JSON embedded in the page.
   *
   * <p>Silently skips individual malformed listing entries; returns an empty list when the
   * embedded script is absent, oversized, or contains invalid JSON.
   *
   * @param doc     parsed HTML document of a realt.by listing page
   * @param context connector-specific configuration (dealType, propertyType, URL paths, etc.)
   * @return list of parsed listings, never null, may be empty on error
   */
  public List<RawListing> parseListings(Document doc, RealtPageContext context) {
    Element scriptEl = doc.selectFirst(NEXT_DATA_SELECTOR);
    if (scriptEl == null) {
      log.warn("__NEXT_DATA__ script not found on page: source={}", context.sourceId());
      return List.of();
    }
    String data = scriptEl.data();
    if (data.length() > MAX_NEXT_DATA_SIZE) {
      log.error("__NEXT_DATA__ exceeds size limit: size={}, source={}", data.length(), context.sourceId());
      return List.of();
    }
    try {
      JsonNode objects = objectMapper.readTree(data).at(JSON_OBJECTS_PATH);
      if (!objects.isArray() || objects.isEmpty()) {
        return List.of();
      }
      List<RawListing> result = new ArrayList<>();
      for (JsonNode obj : objects) {
        safeAddFromJson(result, obj, context);
      }
      return result;
    } catch (JsonProcessingException e) {
      log.error("Failed to parse __NEXT_DATA__ JSON: source={}", context.sourceId(), e);
      return List.of();
    }
  }

  private void safeAddFromJson(List<RawListing> result, JsonNode obj, RealtPageContext context) {
    String hint = obj.path("code").asText("unknown").replaceAll("[\r\n\t]", "_");
    try {
      result.add(toRawListing(obj, context));
    } catch (Exception e) {
      log.warn("Skipping broken Realt listing: source={}, hint={}, error={}",
          context.sourceId(), hint, e.getMessage());
    }
  }

  private RawListing toRawListing(JsonNode obj, RealtPageContext context) {
    String externalId = extractExternalId(obj);
    BigDecimal price = extractPrice(obj, externalId);
    String title = extractTitle(obj, context.fallbackTitle());
    String currency = resolveCurrency(obj);
    // priceUsd is null: price is already in USD for Realt listings.
    BigDecimal priceUsd = null;
    BigDecimal priceByn = computePriceByn(price, currency, externalId, context.sourceId());
    String address = Optional.ofNullable(obj.path("address").textValue())
        .filter(t -> !t.isBlank())
        .orElse(null);
    String city = obj.path("townName").textValue();
    Instant publishedAt = parseInstant(obj.path("createdAt").textValue(), externalId);
    Boolean isOwner = extractIsOwner(obj);

    return new RawListing(
        externalId, title, null,
        context.dealType(), context.propertyType(),
        price, currency, priceUsd, priceByn,
        jsonIntOrNull(obj, "rooms"),
        jsonIntOrNull(obj, "storey"),
        jsonIntOrNull(obj, "storeys"),
        jsonBigDecimalOrNull(obj, "areaTotal"),
        address, null, null, city,
        context.baseUrl() + context.objectPathPrefix() + externalId + "/",
        publishedAt,
        extractPhotos(obj),
        isOwner, null
    );
  }

  private BigDecimal computePriceByn(BigDecimal price, String currency, String externalId, String sourceId) {
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

  private String extractTitle(JsonNode obj, String fallbackTitle) {
    String title = Optional.ofNullable(obj.path("title").textValue())
        .filter(t -> !t.isBlank())
        .orElseGet(() -> Optional.ofNullable(obj.path("headline").textValue())
            .filter(t -> !t.isBlank())
            .orElse(fallbackTitle));
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
}
