package com.flatio.web.dto;

import com.flatio.domain.blacklist.BlacklistEntryType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * A single blacklist entry belonging to the authenticated user.
 */
@Schema(description = "A blacklist entry")
public record BlacklistEntryResponse(
    @Schema(description = "Blacklist entry ID", example = "7")
    Long id,

    @Schema(description = "Kind of item blacklisted", example = "KEYWORD")
    BlacklistEntryType type,

    @Schema(description = "Listing ID (LISTING), source ID (SOURCE), or stop-word (KEYWORD)", example = "novostroyka")
    String value,

    @Schema(description = "Date and time the entry was added", example = "2026-01-15T11:00:00Z")
    Instant createdAt
) {}
