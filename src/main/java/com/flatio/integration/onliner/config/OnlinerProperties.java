package com.flatio.integration.onliner.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the Onliner connector.
 * All values are injected from application configuration — never hard-coded.
 */
@ConfigurationProperties(prefix = "connector.onliner")
public record OnlinerProperties(
    String baseUrl,
    String sourceId,
    String regionCode,
    String apartmentsPath,
    int pageSize
) {}
