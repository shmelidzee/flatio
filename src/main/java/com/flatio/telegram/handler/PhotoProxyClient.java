package com.flatio.telegram.handler;

import com.flatio.common.util.ImageUrlValidator;
import com.flatio.telegram.config.PhotoDownloadProperties;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Downloads photo bytes from external CDN URLs so they can be uploaded to the Telegram Bot API
 * as binary multipart data instead of passing a URL string.
 *
 * <p>Telegram servers are blocked by some CDNs (e.g. content.onliner.by), which causes
 * {@code [400] failed to get HTTP URL content} when a URL is passed directly. By downloading
 * on our side first and uploading the bytes, we bypass CDN IP-based blocking entirely.
 *
 * <p>Any network or HTTP error results in an empty {@link Optional} so that callers can
 * fall back gracefully without crashing the Telegram update handler.
 *
 * <p><b>SSRF hardening (issue #364):</b> {@code url} is listing data sourced from external
 * scraped sites, not something Flatio constructs — validated against {@link ImageUrlValidator}
 * before this class, the last point before an outbound request is actually made, issues it. This
 * check is deliberately duplicated here even though connectors validate on ingestion too: it is
 * the only place that can catch a URL that reached the database before its connector-level
 * validation existed, or through any future code path that skips that validation.
 *
 * <p>As of issue #455, {@link ImageUrlValidator} no longer restricts photos by source domain —
 * only loopback/private/link-local hosts are rejected. See its Javadoc for the rationale.
 *
 * <p>The response body is sniffed for an HTML signature regardless of source (issue #497), so a
 * CDN that mistakenly returns an HTML challenge page instead of image bytes is treated as a
 * failed download (falls back to the placeholder) rather than being uploaded to Telegram as a
 * broken image.
 *
 * <p><b>Kufar is bypassed entirely (issue #515):</b> a browser-like {@code User-Agent}/{@code
 * Referer} (issue #497) did not resolve the CDN anti-bot/geo gate in production (issue #511), so
 * callers — {@link com.flatio.telegram.handler.SearchResultSender} and {@code
 * FavoritesCallbackHandler} — check {@link #isKufarCdnUrl} and pass the photo URL straight to
 * Telegram instead of calling {@link #download} for that host. See {@code docs/integrations.md}
 * for what was found about this behaviour.
 */
@Component
@Slf4j
public class PhotoProxyClient {

  /** Bytes are sniffed against these prefixes (case-insensitive) to detect an HTML page
   * mistakenly returned instead of image bytes, e.g. by a CDN anti-bot/geo gate (issue #497). */
  private static final String[] HTML_SIGNATURES = {"<html", "<!doctype html"};
  private static final int HTML_SIGNATURE_SNIFF_LENGTH = 64;

  private final RestClient restClient;
  private final ImageUrlValidator imageUrlValidator;
  private final PhotoDownloadProperties photoDownloadProperties;

  public PhotoProxyClient(@Qualifier("photoDownloadRestClient") RestClient restClient,
      ImageUrlValidator imageUrlValidator, PhotoDownloadProperties photoDownloadProperties) {
    this.restClient = restClient;
    this.imageUrlValidator = imageUrlValidator;
    this.photoDownloadProperties = photoDownloadProperties;
  }

  /**
   * Downloads photo bytes from the given URL within the configured timeout.
   *
   * <p>Returns empty when the URL fails the {@link ImageUrlValidator} SSRF safety check, on
   * HTTP 4xx/5xx responses, connection timeout, read timeout, or any other I/O error. The caller
   * is expected to fall back to a text card on empty result.
   *
   * @param url       the photo URL to download, must be a valid HTTP/HTTPS URL
   * @param listingId listing identifier used in log messages for traceability
   * @return downloaded bytes wrapped in {@link Optional}, or empty on any failure
   */
  public Optional<byte[]> download(String url, Long listingId) {
    if (!imageUrlValidator.isAllowedImageUrl(url)) {
      log.warn("Refusing to download photo that failed SSRF validation: listingId={}, url={}", listingId, url);
      return Optional.empty();
    }
    try {
      byte[] bytes = restClient.get()
          .uri(url)
          .retrieve()
          .body(byte[].class);
      if (bytes == null || bytes.length == 0) {
        log.warn("Photo download returned empty body: listingId={}, url={}", listingId, url);
        return Optional.empty();
      }
      if (looksLikeHtml(bytes)) {
        log.warn("Photo download returned HTML instead of image bytes, likely CDN anti-bot/geo "
            + "gate (issue #497): listingId={}, url={}", listingId, url);
        return Optional.empty();
      }
      return Optional.of(bytes);
    } catch (Exception e) {
      log.warn("Photo download failed: listingId={}, url={}, error={}", listingId, url, e.getMessage());
      return Optional.empty();
    }
  }

  /**
   * Checks whether the given URL targets the configured Kufar photo CDN host.
   *
   * <p>Public so callers — {@link SearchResultSender} and {@code
   * com.flatio.telegram.callback.FavoritesCallbackHandler} — can use the same check to bypass
   * this class entirely for Kufar and pass the photo URL straight to Telegram instead (issue
   * #515), rather than each duplicating the host comparison.
   *
   * @param url photo URL being downloaded, never null
   * @return true if the URL's host matches {@link PhotoDownloadProperties#kufarCdnHost()}
   */
  public boolean isKufarCdnUrl(String url) {
    String configuredHost = photoDownloadProperties.kufarCdnHost();
    if (configuredHost == null || configuredHost.isBlank()) {
      return false;
    }
    try {
      String host = new URI(url).getHost();
      return host != null && host.equalsIgnoreCase(configuredHost);
    } catch (URISyntaxException e) {
      return false;
    }
  }

  /**
   * Sniffs the start of the downloaded bytes for an HTML document signature, which indicates the
   * CDN returned an anti-bot/geo challenge page instead of the requested image (issue #497).
   *
   * @param bytes downloaded response body, never null or empty
   * @return true if the bytes look like an HTML document rather than image data
   */
  private boolean looksLikeHtml(byte[] bytes) {
    int sniffLength = Math.min(bytes.length, HTML_SIGNATURE_SNIFF_LENGTH);
    String prefix = new String(bytes, 0, sniffLength, StandardCharsets.US_ASCII)
        .strip()
        .toLowerCase(Locale.ROOT);
    for (String signature : HTML_SIGNATURES) {
      if (prefix.startsWith(signature)) {
        return true;
      }
    }
    return false;
  }
}
