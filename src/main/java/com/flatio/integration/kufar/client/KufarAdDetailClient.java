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
 * never an exception — callers fall back to the coarser region/area address. Every code path
 * that returns {@code null} logs a {@code reason=} tag (e.g. {@code not-provided-by-source},
 * {@code next-data-marker-missing}, {@code request-failed}) so the frequency of each distinct
 * cause of the fallback can be measured from logs instead of only ever seeing the fallback
 * itself (issue #334).
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
    return extractAddress(html, adLink);
  }

  String fetchPreciseAddressFallback(String adLink, Exception e) {
    log.warn("Precise address unavailable: reason=request-failed ({}), adLink={}, error={}",
        e.getClass().getSimpleName(), adLink, e.getMessage());
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

  /**
   * Extracts the address from the ad detail page's {@code __NEXT_DATA__} blob.
   *
   * <p>Every {@code return null} path logs a distinct {@code reason=} tag so that fallback
   * frequency by cause (source genuinely has no street address vs. page structure changed vs.
   * a parse error) can be measured from logs — see issue #334.
   *
   * @param html   the ad detail page body, or null if the response had no body
   * @param adLink used only to correlate log lines with the originating ad
   * @return the precise address, or null if unavailable for any reason
   */
  private String extractAddress(String html, String adLink) {
    String json = extractNextDataJson(html, adLink);
    if (json == null) {
      return null;
    }
    try {
      JsonNode node = objectMapper.readTree(json);
      for (String key : ADDRESS_PATH) {
        node = node.path(key);
      }
      String address = node.isMissingNode() ? null : node.asText(null);
      if (address == null || address.isBlank()) {
        log.debug("Precise address unavailable: reason=not-provided-by-source, adLink={}", adLink);
        return null;
      }
      return address;
    } catch (Exception e) {
      log.warn("Precise address unavailable: reason=address-parse-error, adLink={}, error={}",
          adLink, e.getMessage());
      return null;
    }
  }

  private String extractNextDataJson(String html, String adLink) {
    if (html == null) {
      log.debug("Precise address unavailable: reason=empty-response-body, adLink={}", adLink);
      return null;
    }
    int markerIndex = html.indexOf(NEXT_DATA_MARKER);
    if (markerIndex < 0) {
      log.warn("Precise address unavailable: reason=next-data-marker-missing, adLink={} "
          + "(ad detail page structure may have changed)", adLink);
      return null;
    }
    int scriptStart = html.indexOf('>', markerIndex) + 1;
    if (scriptStart <= 0) {
      log.warn("Precise address unavailable: reason=next-data-script-tag-malformed, adLink={}", adLink);
      return null;
    }
    int scriptEnd = html.indexOf(SCRIPT_END_TAG, scriptStart);
    if (scriptEnd < 0) {
      log.warn("Precise address unavailable: reason=next-data-script-truncated, adLink={}", adLink);
      return null;
    }
    return html.substring(scriptStart, scriptEnd);
  }
}
