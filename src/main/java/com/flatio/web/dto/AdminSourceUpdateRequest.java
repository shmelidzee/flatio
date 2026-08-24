package com.flatio.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Partial update for a source's enabled state; omitted fields are left unchanged")
public record AdminSourceUpdateRequest(
    @Schema(description = "When false, schedulers skip this source", example = "false")
    Boolean enabled
) {}
