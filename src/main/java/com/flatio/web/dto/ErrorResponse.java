package com.flatio.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

@Schema(description = "Standard error response body")
public record ErrorResponse(
    @Schema(description = "Time when the error occurred", example = "2026-01-15T11:00:00Z")
    Instant timestamp,

    @Schema(description = "HTTP status code", example = "404")
    int status,

    @Schema(description = "Short error classification", example = "Not Found")
    String error,

    @Schema(description = "Human-readable error message", example = "Listing not found: 42")
    String message,

    @Schema(description = "Request path that produced the error", example = "/api/v1/listings/42")
    String path,

    @Schema(description = "Field-level validation errors; empty for non-validation errors")
    List<ValidationError> errors
) {}
