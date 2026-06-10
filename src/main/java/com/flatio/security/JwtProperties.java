package com.flatio.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for JWT token generation and validation.
 *
 * <p>The secret key is mandatory and must be provided via the {@code JWT_SECRET_KEY}
 * environment variable. No default value is allowed for security reasons.
 *
 * @param secretKey         HMAC-SHA signing key for JWT tokens
 * @param accessTokenExpiry token validity period in seconds (default: 3600)
 */
@ConfigurationProperties(prefix = "flatio.jwt")
public record JwtProperties(String secretKey, long accessTokenExpiry) {
}
