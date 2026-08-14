package com.flatio.config;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Maximum number of active subscriptions allowed per user tariff.
 *
 * <p>{@code USER} role uses {@code userMaxActive}; {@code PRO} and {@code ADMIN} roles use
 * {@code proMaxActive}. Override via {@code flatio.subscription.limits.*} in {@code application.yml}
 * or environment variables when tariff limits change.
 *
 * @param userMaxActive max active subscriptions for the free USER tariff; must be positive
 * @param proMaxActive  max active subscriptions for the PRO tariff; must be positive
 */
@Validated
@ConfigurationProperties(prefix = "flatio.subscription.limits")
public record SubscriptionLimitProperties(
    @NotNull @Positive Integer userMaxActive,
    @NotNull @Positive Integer proMaxActive
) {
}
