package com.flatio.web.dto;

import com.flatio.domain.source.SyncRunStatus;
import com.flatio.domain.source.SyncType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "A single recorded connector sync run")
public record AdminSyncRunResponse(
    @Schema(description = "Internal sync run identifier", example = "1024")
    Long id,

    @Schema(description = "Source code this run belongs to", example = "onliner")
    String sourceId,

    @Schema(description = "FULL crawl or incremental DELTA sync", example = "DELTA")
    SyncType syncType,

    @Schema(description = "Terminal outcome of the run", example = "SUCCESS")
    SyncRunStatus status,

    @Schema(description = "When the run began", example = "2026-08-14T14:20:00Z")
    Instant startedAt,

    @Schema(description = "When the run finished", example = "2026-08-14T14:20:42Z")
    Instant finishedAt,

    @Schema(description = "Run duration in milliseconds", example = "42000")
    long durationMs,

    @Schema(description = "Total listings received from the source", example = "340")
    int listingsFetched,

    @Schema(description = "New listings persisted", example = "12")
    int listingsAdded,

    @Schema(description = "Existing listings refreshed", example = "28")
    int listingsUpdated,

    @Schema(description = "Listings that failed to ingest", example = "0")
    int listingsErrors
) {}
