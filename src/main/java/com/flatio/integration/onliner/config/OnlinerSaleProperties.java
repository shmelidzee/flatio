package com.flatio.integration.onliner.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the Onliner sale connector ({@code pk.api.onliner.by}).
 * All values are injected from application configuration — never hard-coded.
 */
@ConfigurationProperties(prefix = "connector.onliner-sale")
public record OnlinerSaleProperties(
    String baseUrl,
    String sourceId,
    String regionCode,
    String apartmentsPath,
    int pageSize
) {}
