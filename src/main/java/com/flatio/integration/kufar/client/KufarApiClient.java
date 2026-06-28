package com.flatio.integration.kufar.client;

import com.flatio.integration.core.RawListing;
import com.flatio.integration.kufar.config.KufarProperties;
import com.flatio.integration.kufar.dto.KufarAd;
import com.flatio.integration.kufar.dto.KufarAdParameter;
import com.flatio.integration.kufar.dto.KufarPageLink;
import com.flatio.integration.kufar.dto.KufarSearchResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared HTTP client for the Kufar JSON API.
 *
 * <p>Handles cursor-based pagination and maps {@link KufarAd} objects to {@link RawListing}.
 * All six Kufar category connectors delegate their HTTP and mapping logic here.
 *
 * <p>Resilience4j rate limiting, circuit breaker, and retry are applied at the calling connector
 * level, not here — this class is a plain service with no AOP annotations.
 *
 * <p><b>⚠️ Price note:</b> {@code price_byn} is assumed to be in whole BYN units. Verify against
 * real API responses; if values appear 100× too large, the field is in kopecks (divide by 100).
 */
@Service
@Slf4j
public class KufarApiClient {

  private static final int MAX_PAGES = 100;
  private static final String PARAM_ROOMS = "roomsCount";
  private static final String PARAM_FLOOR = "floor";
  private static final String PARAM_FLOORS_TOTAL = "totalFloors";
  private static final String PARAM_AREA = "area";
  private static final String PARAM_GEOPOINT = "geopoint";
  private static final String ACCOUNT_TYPE_PRIVATE = "private";
  private static final String LABEL_NEXT = "next";
  private static final String LANG = "ru";
  private static final String SORT = "lst.d";

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
   * @param config        category-specific configuration (source ID, category code)
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
   * <p>Relies on Kufar returning results sorted by {@code list_time} descending ({@code sor=lst.d}).
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
    return restClient.get()
        .uri(uriBuilder -> {
          var builder = uriBuilder
              .path(properties.searchPath())
              .queryParam("cat", config.categoryCode())
              .queryParam("lang", LANG)
              .queryParam("sor", SORT)
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
    List<KufarAdParameter> params = ad.accountParameters() != null ? ad.accountParameters() : List.of();
    Integer rooms = parseIntParam(params, PARAM_ROOMS);
    Integer floor = parseIntParam(params, PARAM_FLOOR);
    Integer floorsTotal = parseIntParam(params, PARAM_FLOORS_TOTAL);
    BigDecimal area = parseBigDecimalParam(params, PARAM_AREA);
    BigDecimal[] geopoint = parseGeopoint(params);
    BigDecimal lat = geopoint != null ? geopoint[0] : null;
    BigDecimal lon = geopoint != null ? geopoint[1] : null;
    List<String> photoUrls = extractPhotoUrls(ad);
    Instant publishedAt = parseListTime(ad.listTime());
    Boolean isOwner = ad.account() != null ? ACCOUNT_TYPE_PRIVATE.equals(ad.account().type()) : null;
    String title = (ad.subject() != null && !ad.subject().isBlank()) ? ad.subject() : fallbackTitle;
    BigDecimal price = BigDecimal.valueOf(ad.priceByn());

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
        lat,
        lon,
        null,
        ad.adLink(),
        publishedAt,
        photoUrls,
        isOwner,
        null
    );
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

  private Integer parseIntParam(List<KufarAdParameter> params, String label) {
    return params.stream()
        .filter(p -> label.equals(p.pl()) && p.vl() != null)
        .map(p -> {
          try {
            return Integer.parseInt(p.vl().trim());
          } catch (NumberFormatException e) {
            return null;
          }
        })
        .filter(v -> v != null)
        .findFirst()
        .orElse(null);
  }

  private BigDecimal parseBigDecimalParam(List<KufarAdParameter> params, String label) {
    return params.stream()
        .filter(p -> label.equals(p.pl()) && p.vl() != null)
        .map(p -> {
          try {
            return new BigDecimal(p.vl().trim());
          } catch (NumberFormatException e) {
            return null;
          }
        })
        .filter(v -> v != null)
        .findFirst()
        .orElse(null);
  }

  /**
   * Parses a {@code geopoint} parameter value formatted as {@code "lat;lon"}.
   *
   * @param params list of ad parameters
   * @return two-element array {@code [latitude, longitude]}, or null if absent or malformed
   */
  private BigDecimal[] parseGeopoint(List<KufarAdParameter> params) {
    return params.stream()
        .filter(p -> PARAM_GEOPOINT.equals(p.pl()) && p.vl() != null)
        .map(p -> {
          try {
            String[] parts = p.vl().split(";");
            if (parts.length != 2) {
              return null;
            }
            return new BigDecimal[]{new BigDecimal(parts[0].trim()), new BigDecimal(parts[1].trim())};
          } catch (NumberFormatException e) {
            log.warn("Malformed geopoint value: vl={}", p.vl());
            return null;
          }
        })
        .filter(v -> v != null)
        .findFirst()
        .orElse(null);
  }

  private List<String> extractPhotoUrls(KufarAd ad) {
    if (ad.images() == null || ad.images().isEmpty()) {
      return List.of();
    }
    return ad.images().stream()
        .map(img -> img.path())
        .filter(path -> path != null && !path.isBlank())
        .toList();
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
