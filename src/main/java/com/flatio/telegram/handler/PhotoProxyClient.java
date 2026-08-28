package com.flatio.telegram.handler;

import com.flatio.common.util.ImageUrlValidator;
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
 */
@Component
@Slf4j
public class PhotoProxyClient {

  private final RestClient restClient;
  private final ImageUrlValidator imageUrlValidator;

  public PhotoProxyClient(@Qualifier("photoDownloadRestClient") RestClient restClient,
      ImageUrlValidator imageUrlValidator) {
    this.restClient = restClient;
    this.imageUrlValidator = imageUrlValidator;
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
      return Optional.of(bytes);
    } catch (Exception e) {
      log.warn("Photo download failed: listingId={}, url={}, error={}", listingId, url, e.getMessage());
      return Optional.empty();
    }
  }
}
