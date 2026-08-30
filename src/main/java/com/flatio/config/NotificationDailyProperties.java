package com.flatio.config;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for once-daily batched Telegram delivery of {@code PENDING} notifications
 * (issue #410, FR-SUB-6).
 *
 * @param batchSize maximum notifications processed per scheduled run; must be positive
 */
@Validated
@ConfigurationProperties(prefix = "flatio.notifications.daily")
public record NotificationDailyProperties(
    @NotNull @Positive Integer batchSize
) {
}
