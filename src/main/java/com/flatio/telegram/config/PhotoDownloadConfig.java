package com.flatio.telegram.config;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Configures the HTTP client used for proxy-downloading listing photos before uploading them
 * to the Telegram Bot API as binary multipart data.
 *
 * <p>A separate client is used (rather than sharing with connectors) so that aggressive timeouts
 * can be enforced without affecting sync jobs, and to avoid sharing a base URL.
 */
@Configuration
public class PhotoDownloadConfig {

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
  private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

  /**
   * Creates a RestClient for downloading photo bytes from external CDN URLs.
   *
   * <p>Enforces 5-second connect and read timeouts so that a slow or unreachable CDN
   * does not block the Telegram update thread.
   *
   * <p>HTTP redirects are not followed ({@code HttpClient.Redirect.NEVER}) — {@link
   * com.flatio.telegram.handler.PhotoProxyClient} validates the URL against {@link
   * com.flatio.common.util.ImageUrlValidator}'s host allowlist before this client is called, but
   * that check only covers the original URL. Without disabling redirects, a compromised or
   * malformed CDN response could 3xx this server to an arbitrary host, including internal or
   * cloud-metadata addresses (SSRF via redirect, same class of issue as #315). A 3xx response is
   * returned as-is and degrades to an empty body, which {@code PhotoProxyClient} already treats
   * as a download failure.
   *
   * @param builder Spring-managed RestClient.Builder
   * @return RestClient with short timeouts, no fixed base URL, and redirects disabled
   */
  @Bean("photoDownloadRestClient")
  public RestClient photoDownloadRestClient(RestClient.Builder builder) {
    var httpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(CONNECT_TIMEOUT)
        .build();
    var requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(READ_TIMEOUT);

    return builder
        .requestFactory(requestFactory)
        .build();
  }
}
