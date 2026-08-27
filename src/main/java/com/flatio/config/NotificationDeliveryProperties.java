package com.flatio.config;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for real-time Telegram delivery of {@code PENDING} notifications.
 *
 * <p>{@code maxPerHour} enforces FR-SUB-9: once a user has received this many real-time
 * notifications within the last hour, their remaining {@code PENDING} notifications are left
 * unsent for the current run rather than delivered, until the digest delivery mechanism
 * (issue #410) picks them up.
 *
 * @param batchSize         maximum notifications processed per scheduled run; must be positive
 * @param maxPerHour        maximum real-time notifications delivered to one user per hour; must be positive
 * @param retryDelayMinutes minutes a {@code FAILED} notification waits before being retried; must be positive
 */
@Validated
@ConfigurationProperties(prefix = "flatio.notifications.realtime")
public record NotificationDeliveryProperties(
    @NotNull @Positive Integer batchSize,
    @NotNull @Positive Integer maxPerHour,
    @NotNull @Positive Integer retryDelayMinutes
) {
}
