package com.flatio.integration.realt.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the Realt.by sale connector.
 * All values are injected from application configuration — never hard-coded.
 */
@ConfigurationProperties(prefix = "connector.realt-sale")
public record RealtSaleProperties(
    String baseUrl,
    String sourceId,
    String regionCode,
    String listingsPath,
    String objectPathPrefix
) {}
