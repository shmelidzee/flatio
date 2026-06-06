package com.flatio.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

  /**
   * Configures the OpenAPI specification for the Flatio API.
   *
   * @return OpenAPI instance with title "Flatio API" and version "v1"
   */
  @Bean
  public OpenAPI flatioOpenAPI() {
    return new OpenAPI()
        .info(new Info()
            .title("Flatio API")
            .version("v1")
            .description("Real estate aggregation platform — collects listings from multiple sources"));
  }
}
