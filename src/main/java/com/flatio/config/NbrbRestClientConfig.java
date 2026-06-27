package com.flatio.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Configures the {@link RestClient} for the National Bank of Belarus (NBRb) exchange rate API.
 */
@Configuration
public class NbrbRestClientConfig {

  @Value("${flatio.nbrb.base-url:https://api.nbrb.by}")
  private String baseUrl;

  /**
   * Creates a {@link RestClient} pre-configured with the NBRb API base URL.
   *
   * @param builder shared RestClient.Builder provided by Spring Boot auto-configuration
   * @return configured RestClient for NBRb API
   */
  @Bean("nbrbRestClient")
  public RestClient nbrbRestClient(RestClient.Builder builder) {
    return builder.baseUrl(baseUrl).build();
  }
}
