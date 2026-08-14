package com.flatio.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "Data source status and configuration for the admin sources list")
public record AdminSourceResponse(
    @Schema(description = "Unique source code, matches ListingConnector#getSourceId()", example = "onliner")
    String sourceId,

    @Schema(description = "Human-readable source name", example = "Onliner")
    String displayName,

    @Schema(description = "Base URL of the source platform", example = "https://r.onliner.by")
    String url,

    @Schema(description = "ISO country code the source serves", example = "BY")
    String countryCode,

    @Schema(description = "Whether the source's schedulers are allowed to run", example = "true")
    boolean enabled,

    @Schema(description = "Configured interval between scheduled syncs, in minutes", example = "60")
    int syncIntervalMinutes,

    @Schema(description = "Finish time of the most recent successful sync, null if never synced",
        example = "2026-08-14T09:00:00Z")
    Instant lastSyncAt,

    @Schema(description = "When the source was registered", example = "2026-01-10T12:00:00Z")
    Instant createdAt
) {}
