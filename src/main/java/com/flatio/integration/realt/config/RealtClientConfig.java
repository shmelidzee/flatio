package com.flatio.integration.realt.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Registers configuration and beans for the Realt.by connector.
 */
@Configuration
@EnableConfigurationProperties({RealtProperties.class, RealtRoomProperties.class, RealtHouseRentProperties.class})
public class RealtClientConfig {

  private static final String REALT_USER_AGENT =
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
          + " (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36";

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
  private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

  /**
   * Creates a pre-configured RestClient for the Realt.by website.
   *
   * <p>Fetches HTML listing pages. Enforces connect (5 s) and read (10 s) timeouts
   * to prevent indefinite thread blocking when realt.by is unresponsive.
   *
   * @param builder    Spring-managed RestClient.Builder
   * @param properties Realt connector configuration
   * @return RestClient with base URL, User-Agent, and timeouts configured
   */
  @Bean("realtRestClient")
  public RestClient realtRestClient(RestClient.Builder builder, RealtProperties properties) {
    var factorySettings = ClientHttpRequestFactorySettings.DEFAULTS
        .withConnectTimeout(CONNECT_TIMEOUT)
        .withReadTimeout(READ_TIMEOUT);

    return builder
        .requestFactory(ClientHttpRequestFactories.get(factorySettings))
        .baseUrl(properties.baseUrl())
        .defaultHeader("User-Agent", REALT_USER_AGENT)
        .defaultHeader("Accept", "text/html,application/xhtml+xml")
        .build();
  }
}
