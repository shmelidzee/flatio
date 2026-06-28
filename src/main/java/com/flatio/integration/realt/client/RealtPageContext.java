package com.flatio.integration.realt.client;

/**
 * Immutable context passed to {@link RealtHtmlParser} per parsing request.
 * Carries the connector-specific values that vary between realt.by listing categories
 * (apartment rent, apartment sale, room rent, room sale, house sale).
 */
public record RealtPageContext(
    String baseUrl,
    String objectPathPrefix,
    String sourceId,
    String dealType,
    String propertyType,
    String fallbackTitle
) {}
