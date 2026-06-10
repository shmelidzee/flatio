package com.flatio.integration.nominatim.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the Nominatim reverse-geocoding client.
 * All values are injected from application configuration — never hard-coded.
 */
@ConfigurationProperties(prefix = "nominatim")
public record NominatimProperties(
    String baseUrl,
    String userAgent
) {}
