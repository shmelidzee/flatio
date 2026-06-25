package com.flatio.integration.realt.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the Realt.by connector.
 * All values are injected from application configuration — never hard-coded.
 */
@ConfigurationProperties(prefix = "connector.realt")
public record RealtProperties(
    String baseUrl,
    String sourceId,
    String regionCode,
    String listingsPath,
    int pageSize
) {}
