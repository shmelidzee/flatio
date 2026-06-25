package com.flatio.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Issued JWT access token")
public record AuthResponse(
    @Schema(description = "Signed JWT access token", example = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.abc123")
    String accessToken,

    @Schema(description = "Token validity period in seconds", example = "3600")
    long expiresIn
) {}
