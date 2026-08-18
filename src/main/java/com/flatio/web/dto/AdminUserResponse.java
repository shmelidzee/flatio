package com.flatio.web.dto;

import com.flatio.domain.user.UserRole;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "User summary for the admin users list")
public record AdminUserResponse(
    @Schema(description = "Internal user identifier", example = "42")
    Long id,

    @Schema(description = "User's display name", example = "Иван Петров")
    String displayName,

    @Schema(description = "User's email, null if not provided", example = "ivan@example.com")
    String email,

    @Schema(description = "Assigned role", example = "USER")
    UserRole role,

    @Schema(description = "Whether the account is active", example = "true")
    boolean active,

    @Schema(description = "When the account was created", example = "2026-01-10T12:00:00Z")
    Instant createdAt,

    @Schema(description = "Last time the user was seen, null if never recorded", example = "2026-08-14T09:00:00Z")
    Instant lastSeen
) {}
