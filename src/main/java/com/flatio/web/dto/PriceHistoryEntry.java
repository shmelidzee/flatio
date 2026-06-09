package com.flatio.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;

@Schema(description = "A single price history record for a listing")
public record PriceHistoryEntry(
    @Schema(description = "Price at the recorded time", example = "75000.00")
    BigDecimal price,

    @Schema(description = "Currency code at the recorded time", example = "USD")
    String currency,

    @Schema(description = "When this price was recorded", example = "2026-01-15T11:00:00Z")
    Instant recordedAt
) {}
