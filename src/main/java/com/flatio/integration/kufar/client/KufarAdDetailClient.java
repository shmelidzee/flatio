package com.flatio.integration.kufar.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Fetches the precise street-level address of a single Kufar ad from its detail page.
 *
 * <p>The Kufar search API ({@link KufarApiClient}) does not expose a street-level address —
 * only {@code region} and {@code area} (see issue #311). The ad detail page
 * ({@code https://re.kufar.by/vi/{adId}}) is a server-rendered Next.js page that embeds a
 * {@code <script id="__NEXT_DATA__">} JSON blob containing the full address at
 * {@code props.initialState.adView.data.address} (confirmed against a live page — see issue #313).
 *
 * <p>This is one extra HTTP request per ad, so it is rate-limited separately
 * ({@code connector-kufar-detail}) from the batched search API calls ({@code connector-kufar}).
 * The embedded JSON is read via flexible tree navigation, not a fixed record, because
 * {@code __NEXT_DATA__} is an internal Next.js structure — not a documented API contract —
 * and may change shape without notice on any Kufar frontend deploy.
 *
 * <p>Any failure (network, timeout, missing field, unexpected shape) results in {@code null},
 * never an exception — callers fall back to the coarser region/area address.
 *
 * <p>{@code adLink} originates from the Kufar search API response, not from our own users,
 * but it is still external, untrusted input used to build the request URI. Before every
 * request its scheme and host are checked against an allowlist ({@code https}, {@code kufar.by}
 * or a subdomain of it) to prevent a malformed or compromised source response from making this
 * server issue requests to arbitrary hosts (SSRF), e.g. internal services or cloud metadata
 * endpoints.
 */
@Service
@Slf4j
public class KufarAdDetailClient {

  private static final String NEXT_DATA_MARKER = "__NEXT_DATA__";
  private static final String SCRIPT_END_TAG = "</script>";
  private static final String[] ADDRESS_PATH = {"props", "initialState", "adView", "data", "address"};
  private static final String ALLOWED_SCHEME = "https";
  private static final String ALLOWED_HOST_SUFFIX = "kufar.by";

  private final RestClient restClient;
  private final ObjectMapper objectMapper;

  public KufarAdDetailClient(@Qualifier("kufarAdDetailRestClient") RestClient restClient,
      ObjectMapper objectMapper) {
    this.restClient = restClient;
    this.objectMapper = objectMapper;
  }

  /**
   * Fetches and extracts the precise address from a Kufar ad's detail page.
   *
   * @param adLink absolute URL of the ad detail page (Kufar's {@code ad_link} field)
   * @return the full street-level address, or {@code null} if unavailable for any reason
   */
  @RateLimiter(name = "connector-kufar-detail")
  @CircuitBreaker(name = "connector-kufar-detail")
  @Retry(name = "connector-kufar-detail", fallbackMethod = "fetchPreciseAddressFallback")
  public String fetchPreciseAddress(String adLink) {
    if (!isAllowedKufarUrl(adLink)) {
      return null;
    }
    String html = restClient.get().uri(adLink).retrieve().body(String.class);
    return extractAddress(html);
  }

  String fetchPreciseAddressFallback(String adLink, Exception e) {
    log.warn("Falling back to coarse address: adLink={}, error={}", adLink, e.getMessage());
    return null;
  }

  /**
   * Guards against SSRF: only {@code https} URIs on {@code kufar.by} (or a subdomain) are
   * allowed through to {@link #fetchPreciseAddress}, since {@code adLink} is untrusted data
   * from an external API response, not a value we construct ourselves.
   *
   * @param adLink candidate URL, as received from the Kufar search API
   * @return true if the URL may be requested
   */
  private boolean isAllowedKufarUrl(String adLink) {
    if (adLink == null || adLink.isBlank()) {
      return false;
    }
    try {
      URI uri = new URI(adLink);
      String host = uri.getHost();
      boolean allowed = ALLOWED_SCHEME.equalsIgnoreCase(uri.getScheme())
          && host != null
          && (host.equalsIgnoreCase(ALLOWED_HOST_SUFFIX) || host.toLowerCase().endsWith("." + ALLOWED_HOST_SUFFIX));
      if (!allowed) {
        log.warn("Rejecting ad detail URL outside the kufar.by allowlist: adLink={}", adLink);
      }
      return allowed;
    } catch (URISyntaxException e) {
      log.warn("Rejecting malformed ad detail URL: adLink={}", adLink);
      return false;
    }
  }

  private String extractAddress(String html) {
    String json = extractNextDataJson(html);
    if (json == null) {
      return null;
    }
    try {
      JsonNode node = objectMapper.readTree(json);
      for (String key : ADDRESS_PATH) {
        node = node.path(key);
      }
      String address = node.isMissingNode() ? null : node.asText(null);
      return (address != null && !address.isBlank()) ? address : null;
    } catch (Exception e) {
      log.warn("Failed to parse __NEXT_DATA__ address: error={}", e.getMessage());
      return null;
    }
  }

  private String extractNextDataJson(String html) {
    if (html == null) {
      return null;
    }
    int markerIndex = html.indexOf(NEXT_DATA_MARKER);
    if (markerIndex < 0) {
      return null;
    }
    int scriptStart = html.indexOf('>', markerIndex) + 1;
    if (scriptStart <= 0) {
      return null;
    }
    int scriptEnd = html.indexOf(SCRIPT_END_TAG, scriptStart);
    if (scriptEnd < 0) {
      return null;
    }
    return html.substring(scriptStart, scriptEnd);
  }
}
