package com.flatio.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Positive;

@Schema(description = "Partial update for a source's enabled state and/or sync interval; "
    + "omitted fields are left unchanged")
public record AdminSourceUpdateRequest(
    @Schema(description = "When false, schedulers skip this source", example = "false")
    Boolean enabled,

    @Schema(description = "New interval between scheduled syncs, in minutes", example = "90")
    @Positive
    Integer syncIntervalMinutes
) {}
