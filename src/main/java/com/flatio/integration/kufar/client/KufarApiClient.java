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
 *
 * <p><b>Address resolution (issue #334):</b> {@code ad_parameters} does not carry a
 * street-level address (confirmed against a live response — see issue #311), only
 * {@code region} (oblast or Minsk) and {@code area} (city or district). The street-level
 * address, when present, is instead a {@code p="address"} entry inside {@code account_parameters}
 * — that array was originally assumed to carry seller-profile fields only, so this entry was
 * never read; a sample of 60 live listings across 4 categories (2026-08-18) had it populated
 * 60/60. {@link KufarAdDetailClient#fetchPreciseAddress} (issue #313) — an extra per-ad HTTP
 * request that scrapes the same address out of the ad detail page's {@code __NEXT_DATA__} blob —
 * is kept only as a fallback for the rare case {@code account_parameters} lacks it; it is no
 * longer the primary source. It was additionally confirmed broken as a primary source: Kufar
 * now serves ad detail URLs as a 301 redirect to a SEO-friendly path, and the client backing it
 * does not follow redirects by design (SSRF hardening, issue #315), so every detail-page fetch
 * was silently falling back to the coarse region/area address regardless of rate limiting.
 * Priority order in {@link RawListing#address()}: {@code account_parameters} address →
 * detail-page address → region/area.
 *
 * <p><b>City (issue #358):</b> {@link RawListing#city()} is set from the {@code area} ad
 * parameter (city or district — the same structured field used as the region/area address
 * fallback above), not parsed out of the free-text address string. Onliner and Realt derive
 * city from address text because that is the only place their source exposes it; Kufar already
 * provides it as a discrete field, so using it directly avoids depending on the shape of
 * whichever address source (account_parameters, detail page, or region/area) was resolved.
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
  private static final String PARAM_SIZE = "size";
  private static final String PARAM_REGION = "region";
  private static final String PARAM_AREA = "area";
  private static final String PARAM_ADDRESS = "address";
  private static final String ADDRESS_PART_SEPARATOR = ", ";

  private final RestClient restClient;
  private final KufarProperties properties;
  private final KufarAdDetailClient adDetailClient;

  public KufarApiClient(@Qualifier("kufarRestClient") RestClient restClient,
      KufarProperties properties, KufarAdDetailClient adDetailClient) {
    this.restClient = restClient;
    this.properties = properties;
    this.adDetailClient = adDetailClient;
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
    } catch (RuntimeException e) {
      // Rethrown (issue #366) rather than swallowed: a partial `result` here would otherwise be
      // returned as if it were the complete listing set, and the calling connector's
      // @Retry/@CircuitBreaker never sees the failure to retry or trip on. The caller's fallback
      // method returns an empty list on exhausted retries, which the sync job already treats as
      // "skip deactivation to avoid data loss" — so propagating here costs nothing extra and
      // closes the false-mass-deactivation risk of treating a cut-short page range as complete.
      log.error("Error during full fetch: source={}, page={}, error={}", config.sourceId(), page, e.getMessage(), e);
      throw e;
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
    } catch (RuntimeException e) {
      // Rethrown for the same reason as fetchAll (issue #366) — see its comment.
      log.error("Error during delta fetch: source={}, page={}, error={}", config.sourceId(), page, e.getMessage(), e);
      throw e;
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
    List<KufarAdParameter> adParams = ad.adParameters() != null ? ad.adParameters() : List.of();
    Integer rooms = parseIntParam(adParams, PARAM_ROOMS);
    Integer floor = parseIntParam(adParams, PARAM_FLOOR);
    Integer floorsTotal = parseIntParam(adParams, PARAM_FLOORS_TOTAL);
    BigDecimal area = parseBigDecimalParam(adParams, PARAM_SIZE);
    List<KufarAdParameter> accountParams = ad.accountParameters() != null ? ad.accountParameters() : List.of();
    String address = resolveAddress(accountParams, adParams, ad.adLink());
    String city = parseStringParam(adParams, PARAM_AREA);
    List<String> photoUrls = extractPhotoUrls(ad);
    Instant publishedAt = parseListTime(ad.listTime());
    Boolean isOwner = resolveIsOwner(ad);
    String title = (ad.subject() != null && !ad.subject().isBlank()) ? ad.subject() : fallbackTitle;
    boolean isNegotiable = ad.priceByn() == null || ad.priceByn() == 0L;
    BigDecimal price = isNegotiable
        ? BigDecimal.ZERO
        : BigDecimal.valueOf(ad.priceByn()).divide(KOPECKS_DIVISOR, 2, RoundingMode.HALF_UP);

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
        address,
        null,
        null,
        city,
        ad.adLink(),
        publishedAt,
        photoUrls,
        isOwner,
        null,
        isNegotiable
    );
  }

  /**
   * Resolves the ad's most precise available address.
   *
   * <p>Priority order (issue #334): the {@code address} entry in {@code account_parameters}
   * (already present in the search response, no extra request) → the ad detail page's precise
   * address via {@link KufarAdDetailClient} (an extra per-ad HTTP request, only attempted when
   * the first source has nothing) → the coarser {@code region}/{@code area} fallback.
   *
   * @param accountParams account parameters to search for the primary address source
   * @param adParams      ad parameters to search for the region/area fallback
   * @param adLink        absolute URL of the ad detail page
   * @return the most precise address available, or null if none of the three sources has one
   */
  private String resolveAddress(List<KufarAdParameter> accountParams, List<KufarAdParameter> adParams,
      String adLink) {
    String accountAddress = parseStringParam(accountParams, PARAM_ADDRESS);
    if (accountAddress != null) {
      return accountAddress;
    }
    String preciseAddress = adDetailClient.fetchPreciseAddress(adLink);
    return preciseAddress != null ? preciseAddress : resolveFallbackAddress(adParams);
  }

  /**
   * Builds an address string from the {@code region} and {@code area} ad parameters.
   *
   * <p>Kufar's search API does not return a street-level address field. {@code region}
   * (oblast or Minsk) and {@code area} (city or district) are the most precise location
   * data always present in {@code ad_parameters}, so they are joined into a single string.
   *
   * @param adParams ad parameters to search
   * @return combined "region, area" string, either part alone, or null if neither is present
   */
  private String resolveFallbackAddress(List<KufarAdParameter> adParams) {
    String region = parseStringParam(adParams, PARAM_REGION);
    String area = parseStringParam(adParams, PARAM_AREA);
    if (region != null && area != null) {
      return region + ADDRESS_PART_SEPARATOR + area;
    }
    return region != null ? region : area;
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

  private String parseStringParam(List<KufarAdParameter> params, String key) {
    return params.stream()
        .filter(p -> key.equals(p.p()))
        .map(p -> p.vl() != null && !p.vl().isBlank() ? p.vl() : p.v())
        .filter(v -> v != null && !v.isBlank())
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
    String base = cdnBase.endsWith("/") ? cdnBase.substring(0, cdnBase.length() - 1) : cdnBase;
    return base + "/" + path;
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
