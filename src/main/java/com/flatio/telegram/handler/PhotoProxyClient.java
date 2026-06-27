package com.flatio.telegram.handler;

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
 */
@Component
@Slf4j
public class PhotoProxyClient {

  private final RestClient restClient;

  public PhotoProxyClient(@Qualifier("photoDownloadRestClient") RestClient restClient) {
    this.restClient = restClient;
  }

  /**
   * Downloads photo bytes from the given URL within the configured timeout.
   *
   * <p>Returns empty on HTTP 4xx/5xx responses, connection timeout, read timeout, or any
   * other I/O error. The caller is expected to fall back to a text card on empty result.
   *
   * @param url       the photo URL to download, must be a valid HTTP/HTTPS URL
   * @param listingId listing identifier used in log messages for traceability
   * @return downloaded bytes wrapped in {@link Optional}, or empty on any failure
   */
  public Optional<byte[]> download(String url, Long listingId) {
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
