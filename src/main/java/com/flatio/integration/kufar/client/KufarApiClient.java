package com.flatio.integration.kufar.client;

import com.flatio.integration.core.RawListing;
import com.flatio.integration.kufar.config.KufarProperties;
import com.flatio.integration.kufar.dto.KufarAd;
import com.flatio.integration.kufar.dto.KufarAdParameter;
import com.flatio.integration.kufar.dto.KufarImage;
import com.flatio.integration.kufar.dto.KufarPageLink;
import com.flatio.integration.kufar.dto.KufarSearchResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Shared HTTP client for the Kufar JSON API.
 *
 * <p>Handles cursor-based pagination and maps {@link KufarAd} objects to {@link RawListing}.
 * All six Kufar category connectors delegate their HTTP and mapping logic here.
 *
 * <p>Resilience4j rate limiting, circuit breaker, and retry are applied at the calling connector
 * level, not here — this class is a plain service with no AOP annotations.
 *
 * <p>Prices in the Kufar API are returned in BYN kopecks (hundredths of BYN) and are
 * divided by 100 before being stored in {@link RawListing}.
 *
 * <p>Property attributes (rooms, floor, total floors, area) are extracted from
 * {@code ad_parameters} keyed by the machine {@code p} field. Geocoordinates are intentionally
 * not parsed here — the Nominatim geocoding pipeline fills them in from the address.
 */
@Service
@Slf4j
public class KufarApiClient {

  private static final int MAX_PAGES = 100;
  private static final BigDecimal KOPECKS_DIVISOR = BigDecimal.valueOf(100);
  private static final String ACCOUNT_TYPE_PRIVATE = "private";
  private static final String LABEL_NEXT = "next";

  private static final String PARAM_ROOMS = "rooms";
  private static final String PARAM_FLOOR = "floor";
  private static final String PARAM_FLOORS_TOTAL = "re_number_floors";
  private static final String PARAM_AREA = "size";

  private final RestClient restClient;
  private final KufarProperties properties;

  public KufarApiClient(@Qualifier("kufarRestClient") RestClient restClient,
      KufarProperties properties) {
    this.restClient = restClient;
    this.properties = properties;
  }

  /**
   * Fetches all listings for the given category by paginating through every available page.
   *
   * @param config        category-specific configuration (source ID, category code, deal type)
   * @param dealType      {@code RENT} or {@code SELL}
   * @param propertyType  {@code APARTMENT}, {@code ROOM}, or {@code HOUSE}
   * @param fallbackTitle title used when the ad subject is blank
   * @return complete list of raw listings, never null
   */
  public List<RawListing> fetchAll(KufarProperties.CategoryConfig config,
      String dealType, String propertyType, String fallbackTitle) {
    log.info("Full fetch started: source={}", config.sourceId());
    List<RawListing> result = new ArrayList<>();
    String cursor = null;
    int page = 0;
    try {
      do {
        KufarSearchResponse response = fetchPage(config, cursor);
        if (response == null || response.ads() == null || response.ads().isEmpty()) {
          break;
        }
        result.addAll(parseListings(response.ads(), config, dealType, propertyType, fallbackTitle));
        cursor = extractNextCursor(response);
        page++;
      } while (cursor != null && page < MAX_PAGES);
    } catch (Exception e) {
      log.error("Error during full fetch: source={}, page={}, error={}", config.sourceId(), page, e.getMessage(), e);
    }
    log.info("Full fetch completed: source={}, fetched={}", config.sourceId(), result.size());
    return result;
  }

  /**
   * Fetches listings published at or after the given timestamp (delta sync).
   *
   * <p>Assumes Kufar returns results in newest-first order by default.
   * Stops as soon as a listing's {@code list_time} is before {@code since}.
   *
   * @param config        category-specific configuration
   * @param since         lower-bound timestamp (exclusive)
   * @param dealType      {@code RENT} or {@code SELL}
   * @param propertyType  {@code APARTMENT}, {@code ROOM}, or {@code HOUSE}
   * @param fallbackTitle title used when the ad subject is blank
   * @return list of recently published listings, never null
   */
  public List<RawListing> fetchDelta(KufarProperties.CategoryConfig config, Instant since,
      String dealType, String propertyType, String fallbackTitle) {
    log.info("Delta fetch started: source={}, since={}", config.sourceId(), since);
    List<RawListing> result = new ArrayList<>();
    String cursor = null;
    int page = 0;
    boolean done = false;
    try {
      do {
        KufarSearchResponse response = fetchPage(config, cursor);
        if (response == null || response.ads() == null || response.ads().isEmpty()) {
          break;
        }
        for (KufarAd ad : response.ads()) {
          Instant adTime = parseListTime(ad.listTime());
          if (adTime != null && adTime.isBefore(since)) {
            done = true;
            break;
          }
          safeAdd(result, ad, config, dealType, propertyType, fallbackTitle);
        }
        cursor = extractNextCursor(response);
        page++;
      } while (!done && cursor != null && page < MAX_PAGES);
    } catch (Exception e) {
      log.error("Error during delta fetch: source={}, page={}, error={}", config.sourceId(), page, e.getMessage(), e);
    }
    log.info("Delta fetch completed: source={}, fetched={}", config.sourceId(), result.size());
    return result;
  }

  private KufarSearchResponse fetchPage(KufarProperties.CategoryConfig config, String cursor) {
    if (config.categoryCode() == null || config.categoryCode().isBlank()) {
      log.warn("Empty categoryCode for source={}, skipping fetch", config.sourceId());
      return null;
    }
    return restClient.get()
        .uri(uriBuilder -> {
          var builder = uriBuilder
              .path(properties.searchPath())
              .queryParam("cat", config.categoryCode())
              .queryParam("typ", config.dealType())
              .queryParam("lang", properties.lang())
              .queryParam("size", properties.pageSize());
          if (cursor != null) {
            builder = builder.queryParam("cursor", cursor);
          }
          return builder.build();
        })
        .retrieve()
        .body(KufarSearchResponse.class);
  }

  private List<RawListing> parseListings(List<KufarAd> ads, KufarProperties.CategoryConfig config,
      String dealType, String propertyType, String fallbackTitle) {
    List<RawListing> result = new ArrayList<>();
    for (KufarAd ad : ads) {
      safeAdd(result, ad, config, dealType, propertyType, fallbackTitle);
    }
    return result;
  }

  private void safeAdd(List<RawListing> result, KufarAd ad, KufarProperties.CategoryConfig config,
      String dealType, String propertyType, String fallbackTitle) {
    try {
      result.add(toRawListing(ad, config, dealType, propertyType, fallbackTitle));
    } catch (Exception e) {
      log.warn("Skipping broken Kufar listing: adId={}, source={}, error={}",
          ad.adId(), config.sourceId(), e.getMessage());
    }
  }

  private RawListing toRawListing(KufarAd ad, KufarProperties.CategoryConfig config,
      String dealType, String propertyType, String fallbackTitle) {
    if (ad.priceByn() == null) {
      throw new IllegalArgumentException("Missing price for Kufar ad id=" + ad.adId());
    }
    List<KufarAdParameter> adParams = ad.adParameters() != null ? ad.adParameters() : List.of();
    Integer rooms = parseIntParam(adParams, PARAM_ROOMS);
    Integer floor = parseIntParam(adParams, PARAM_FLOOR);
    Integer floorsTotal = parseIntParam(adParams, PARAM_FLOORS_TOTAL);
    BigDecimal area = parseBigDecimalParam(adParams, PARAM_AREA);
    List<String> photoUrls = extractPhotoUrls(ad);
    Instant publishedAt = parseListTime(ad.listTime());
    Boolean isOwner = resolveIsOwner(ad);
    String title = (ad.subject() != null && !ad.subject().isBlank()) ? ad.subject() : fallbackTitle;
    BigDecimal price = BigDecimal.valueOf(ad.priceByn()).divide(KOPECKS_DIVISOR, 2, RoundingMode.HALF_UP);

    return new RawListing(
        String.valueOf(ad.adId()),
        title,
        ad.body(),
        dealType,
        propertyType,
        price,
        "BYN",
        null,
        null,
        rooms,
        floor,
        floorsTotal,
        area,
        null,
        null,
        null,
        null,
        ad.adLink(),
        publishedAt,
        photoUrls,
        isOwner,
        null
    );
  }

  private Boolean resolveIsOwner(KufarAd ad) {
    if (ad.account() != null) {
      return ACCOUNT_TYPE_PRIVATE.equals(ad.account().type());
    }
    if (ad.companyAd() != null) {
      return !ad.companyAd();
    }
    return null;
  }

  private String extractNextCursor(KufarSearchResponse response) {
    if (response.pagination() == null || response.pagination().pages() == null) {
      return null;
    }
    return response.pagination().pages().stream()
        .filter(p -> LABEL_NEXT.equals(p.label()))
        .map(KufarPageLink::token)
        .findFirst()
        .orElse(null);
  }

  private Integer parseIntParam(List<KufarAdParameter> params, String key) {
    return params.stream()
        .filter(p -> key.equals(p.p()))
        .map(p -> {
          String raw = p.v() != null ? p.v() : p.vl();
          if (raw == null || raw.isBlank()) {
            return null;
          }
          try {
            return Integer.parseInt(raw.trim());
          } catch (NumberFormatException e) {
            return null;
          }
        })
        .filter(Objects::nonNull)
        .findFirst()
        .orElse(null);
  }

  private BigDecimal parseBigDecimalParam(List<KufarAdParameter> params, String key) {
    return params.stream()
        .filter(p -> key.equals(p.p()))
        .map(p -> {
          String raw = p.v() != null ? p.v() : p.vl();
          if (raw == null || raw.isBlank()) {
            return null;
          }
          try {
            return new BigDecimal(raw.trim());
          } catch (NumberFormatException e) {
            return null;
          }
        })
        .filter(Objects::nonNull)
        .findFirst()
        .orElse(null);
  }

  private List<String> extractPhotoUrls(KufarAd ad) {
    if (ad.images() == null || ad.images().isEmpty()) {
      return List.of();
    }
    String cdnBase = properties.photoCdnBaseUrl();
    return ad.images().stream()
        .map(KufarImage::path)
        .filter(path -> path != null && !path.isBlank())
        .map(path -> toFullPhotoUrl(cdnBase, path))
        .toList();
  }

  private String toFullPhotoUrl(String cdnBase, String path) {
    if (cdnBase == null || cdnBase.isBlank() || path.startsWith("http")) {
      return path;
    }
    return cdnBase + "/" + path;
  }

  private Instant parseListTime(String listTime) {
    if (listTime == null) {
      return null;
    }
    try {
      return OffsetDateTime.parse(listTime).toInstant();
    } catch (Exception e) {
      log.warn("Failed to parse Kufar list_time: value={}", listTime);
      return null;
    }
  }
}
