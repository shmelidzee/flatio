package com.flatio.integration.realt.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the Realt.by room-for-sale connector.
 * All values are injected from application configuration — never hard-coded.
 */
@ConfigurationProperties(prefix = "connector.realt-room-sale")
public record RealtRoomSaleProperties(
    String baseUrl,
    String sourceId,
    String regionCode,
    String listingsPath,
    String objectPathPrefix
) {}
