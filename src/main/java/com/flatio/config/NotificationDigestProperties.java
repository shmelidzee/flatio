package com.flatio.config;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for batched (DIGEST) Telegram delivery of {@code PENDING} notifications
 * (issue #410, FR-SUB-6).
 *
 * <p>{@code realtimeOverflowMinutes} implements the FR-SUB-9 forced REALTIME → DIGEST fallback:
 * a REALTIME notification that has sat {@code PENDING} longer than this (because its owner keeps
 * hitting the hourly real-time cap) is swept into the next digest run instead of waiting
 * indefinitely for real-time delivery.
 *
 * @param batchSize               maximum notifications processed per scheduled run; must be positive
 * @param realtimeOverflowMinutes minutes a rate-limited REALTIME notification waits before a
 *                                digest run picks it up; must be positive
 */
@Validated
@ConfigurationProperties(prefix = "flatio.notifications.digest")
public record NotificationDigestProperties(
    @NotNull @Positive Integer batchSize,
    @NotNull @Positive Integer realtimeOverflowMinutes
) {
}
