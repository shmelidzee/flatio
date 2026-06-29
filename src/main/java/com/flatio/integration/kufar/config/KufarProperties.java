package com.flatio.integration.kufar.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for all Kufar connectors.
 *
 * <p>One class covers all six categories (apartment/room/house × rent/sale).
 * All values are injected from application configuration — never hard-coded.
 */
@ConfigurationProperties(prefix = "connector.kufar")
public record KufarProperties(
    String baseUrl,
    String searchPath,
    int pageSize,
    String lang,
    CategoryConfig apartmentRent,
    CategoryConfig apartmentSale,
    CategoryConfig roomRent,
    CategoryConfig roomSale,
    CategoryConfig houseRent,
    CategoryConfig houseSale
) {

  /**
   * Per-category connector configuration.
   *
   * @param sourceId     Flatio source code matching the {@code source} table (e.g. {@code KUFAR_APARTMENT_RENT})
   * @param regionCode   Flatio internal region code (e.g. {@code BY})
   * @param categoryCode Kufar {@code cat} query parameter value (1010 = apartments, 1020 = houses, 1040 = rooms)
   * @param dealType     Kufar {@code typ} query parameter value ({@code "let"} = rent, {@code "sell"} = sale)
   */
  public record CategoryConfig(
      String sourceId,
      String regionCode,
      String categoryCode,
      String dealType
  ) {}
}
