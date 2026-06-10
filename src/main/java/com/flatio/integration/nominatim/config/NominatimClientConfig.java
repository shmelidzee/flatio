package com.flatio.integration.nominatim.config;

import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * Registers the Nominatim RestClient bean.
 */
@Configuration
@EnableConfigurationProperties(NominatimProperties.class)
public class NominatimClientConfig {

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
  private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

  /**
   * Creates a pre-configured RestClient for the Nominatim API.
   *
   * <p>Enforces connect (5 s) and read (10 s) timeouts. The User-Agent header is set from
   * configuration to comply with the OSM Nominatim usage policy, which requires identifying
   * the application and providing contact information.
   *
   * @param builder    Spring-managed RestClient.Builder
   * @param properties Nominatim client configuration
   * @return RestClient with base URL, User-Agent, Accept, and timeouts configured
   */
  @Bean("nominatimRestClient")
  public RestClient nominatimRestClient(RestClient.Builder builder, NominatimProperties properties) {
    var factorySettings = ClientHttpRequestFactorySettings.DEFAULTS
        .withConnectTimeout(CONNECT_TIMEOUT)
        .withReadTimeout(READ_TIMEOUT);

    return builder
        .requestFactory(ClientHttpRequestFactories.get(factorySettings))
        .baseUrl(properties.baseUrl())
        .defaultHeader("User-Agent", properties.userAgent())
        .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
        .build();
  }
}
