package com.flatio.telegram.config;

import java.time.Duration;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
   * @param builder Spring-managed RestClient.Builder
   * @return RestClient with short timeouts and no fixed base URL
   */
  @Bean("photoDownloadRestClient")
  public RestClient photoDownloadRestClient(RestClient.Builder builder) {
    var factorySettings = ClientHttpRequestFactorySettings.DEFAULTS
        .withConnectTimeout(CONNECT_TIMEOUT)
        .withReadTimeout(READ_TIMEOUT);

    return builder
        .requestFactory(ClientHttpRequestFactories.get(factorySettings))
        .build();
  }
}
