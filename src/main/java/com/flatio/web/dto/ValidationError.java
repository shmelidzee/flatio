package com.flatio.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A single field-level validation error")
public record ValidationError(
    @Schema(description = "The field that failed validation", example = "priceMin")
    String field,

    @Schema(description = "Human-readable validation message", example = "must be greater than or equal to 0")
    String message
) {}
