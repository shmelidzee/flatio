package com.flatio.integration.kufar.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Registers configuration and beans for all Kufar connectors.
 *
 * <p>A single {@code RestClient} is shared across all six Kufar category connectors
 * since they all target the same {@code api.kufar.by} origin.
 */
@Configuration
@EnableConfigurationProperties(KufarProperties.class)
public class KufarClientConfig {

  private static final String KUFAR_USER_AGENT =
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
          + " (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36";

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
  private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

  /**
   * Creates a pre-configured RestClient for the Kufar JSON API.
   *
   * <p>Enforces connect (5 s) and read (10 s) timeouts to prevent indefinite thread blocking
   * when the Kufar API is unresponsive.
   *
   * @param builder    Spring-managed RestClient.Builder
   * @param properties Kufar connector configuration
   * @return RestClient with base URL, User-Agent, Accept, and timeouts configured
   */
  @Bean("kufarRestClient")
  public RestClient kufarRestClient(RestClient.Builder builder, KufarProperties properties) {
    var factorySettings = ClientHttpRequestFactorySettings.DEFAULTS
        .withConnectTimeout(CONNECT_TIMEOUT)
        .withReadTimeout(READ_TIMEOUT);

    return builder
        .requestFactory(ClientHttpRequestFactories.get(factorySettings))
        .baseUrl(properties.baseUrl())
        .defaultHeader("User-Agent", KUFAR_USER_AGENT)
        .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
        .build();
  }
}
