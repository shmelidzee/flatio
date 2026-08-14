package com.flatio.web.dto;

import com.flatio.domain.subscription.DeliveryMode;
import com.flatio.domain.subscription.SubscriptionChannelType;
import com.flatio.domain.subscription.TriggerType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.util.Set;

/**
 * Full representation of a subscription returned by the API.
 */
@Schema(description = "Subscription details")
public record SubscriptionResponse(
    @Schema(description = "Subscription ID", example = "42")
    Long id,

    @Schema(description = "Subscription display name", example = "2-комнатные в центре")
    String name,

    @Schema(description = "Whether the subscription is currently active", example = "true")
    boolean active,

    @Schema(description = "Search filter matched against listings")
    SubscriptionSearchCriteria searchCriteria,

    @Schema(description = "Listing events that trigger a notification")
    Set<TriggerType> triggers,

    @Schema(description = "How often matching listings are delivered", example = "REALTIME")
    DeliveryMode deliveryMode,

    @Schema(description = "Delivery channel", example = "TELEGRAM")
    SubscriptionChannelType channelType,

    @Schema(description = "Minimum price drop percentage for the PRICE_DROP trigger", example = "5.00")
    BigDecimal priceDropThreshold,

    @Schema(description = "Start of the quiet hours window", example = "23:00:00")
    LocalTime quietHoursStart,

    @Schema(description = "End of the quiet hours window", example = "08:00:00")
    LocalTime quietHoursEnd,

    @Schema(description = "Creation timestamp", example = "2026-01-15T11:00:00Z")
    Instant createdAt,

    @Schema(description = "Last update timestamp", example = "2026-01-15T11:00:00Z")
    Instant updatedAt
) {}
