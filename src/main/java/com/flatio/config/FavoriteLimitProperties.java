package com.flatio.config;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Maximum number of favorited listings allowed per user tariff.
 *
 * <p>{@code USER} role uses {@code userMaxFavorites}. {@code PRO} and {@code ADMIN} roles use
 * {@code proMaxFavorites}, which is {@code null} by default — Spring Boot's relaxed binding
 * treats an empty environment variable value as no value, so leaving {@code FAVORITES_LIMIT_PRO_MAX}
 * unset keeps the PRO/ADMIN tariff uncapped. Override via {@code flatio.favorites.limits.*} in
 * {@code application.yml} or environment variables when tariff limits change.
 *
 * @param userMaxFavorites max favorited listings for the free USER tariff; must be positive
 * @param proMaxFavorites  max favorited listings for the PRO/ADMIN tariff; {@code null} means unlimited
 */
@Validated
@ConfigurationProperties(prefix = "flatio.favorites.limits")
public record FavoriteLimitProperties(
    @NotNull @Positive Integer userMaxFavorites,
    @Positive Integer proMaxFavorites
) {
}
