package com.flatio.web.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.flatio.domain.subscription.DeliveryMode;
import com.flatio.domain.subscription.SubscriptionChannelType;
import com.flatio.domain.subscription.TriggerType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.Set;

/**
 * Request body for creating a subscription from a saved search filter.
 */
@Schema(description = "Create a subscription request")
public record CreateSubscriptionRequest(
    @Schema(description = "Subscription display name", example = "2-комнатные в центре", requiredMode = REQUIRED)
    @NotBlank @Size(max = 255) String name,

    @Schema(description = "Search filter to match listings against", requiredMode = REQUIRED)
    @NotNull @Valid SubscriptionSearchCriteria searchCriteria,

    @Schema(description = "Listing events that trigger a notification", requiredMode = REQUIRED)
    @NotEmpty Set<TriggerType> triggers,

    @Schema(description = "How often matching listings are delivered", example = "REALTIME", requiredMode = REQUIRED)
    @NotNull DeliveryMode deliveryMode,

    @Schema(description = "Delivery channel", example = "TELEGRAM", requiredMode = REQUIRED)
    @NotNull SubscriptionChannelType channelType,

    @Schema(description = "Minimum price drop percentage for the PRICE_DROP trigger; defaults to 5% when omitted",
        example = "5.00")
    BigDecimal priceDropThreshold,

    @Schema(description = "Start of the quiet hours window (no notifications sent)", example = "23:00:00")
    LocalTime quietHoursStart,

    @Schema(description = "End of the quiet hours window", example = "08:00:00")
    LocalTime quietHoursEnd
) {}
