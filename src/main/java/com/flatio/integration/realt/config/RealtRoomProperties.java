package com.flatio.integration.realt.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the Realt.by room-for-rent connector.
 * All values are injected from application configuration — never hard-coded.
 */
@ConfigurationProperties(prefix = "connector.realt-room")
public record RealtRoomProperties(
    String baseUrl,
    String sourceId,
    String regionCode,
    String listingsPath,
    String objectPathPrefix
) {}
