package com.flatio.integration.nbrb.config;

import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Registers the {@link RestClient} and configuration properties for the NBRb exchange rate
 * connector.
 */
@Configuration
@EnableConfigurationProperties(NbrbProperties.class)
public class NbrbClientConfig {

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
  private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

  /**
   * Creates a {@link RestClient} pre-configured with the NBRb API base URL.
   *
   * <p>Enforces connect (5 s) and read (10 s) timeouts so a call that fires on the
   * {@code startupSyncExecutor} thread (see {@code ExchangeRateSyncJob#onApplicationReady})
   * cannot block that thread indefinitely if NBRb is unreachable.
   *
   * @param builder    Spring-managed {@link RestClient.Builder}
   * @param properties connector configuration
   * @return RestClient scoped to the NBRb API
   */
  @Bean("nbrbRestClient")
  public RestClient nbrbRestClient(RestClient.Builder builder, NbrbProperties properties) {
    var factorySettings = ClientHttpRequestFactorySettings.DEFAULTS
        .withConnectTimeout(CONNECT_TIMEOUT)
        .withReadTimeout(READ_TIMEOUT);
    return builder
        .requestFactory(ClientHttpRequestFactories.get(factorySettings))
        .baseUrl(properties.baseUrl())
        .build();
  }
}
