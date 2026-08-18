package com.flatio.web.dto;

import com.flatio.domain.audit.AdminAuditObjectType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "A single recorded admin action for the audit log feed")
public record AdminAuditLogResponse(
    @Schema(description = "Internal audit log entry identifier", example = "512")
    Long id,

    @Schema(description = "Id of the admin who performed the action", example = "1")
    Long adminId,

    @Schema(description = "Display name of the admin who performed the action, null if the "
        + "user no longer exists", example = "Иван Петров")
    String adminDisplayName,

    @Schema(description = "Action performed, matches the action= tag in the SLF4J admin audit log line",
        example = "updateListingStatus")
    String action,

    @Schema(description = "Type of resource the action was performed on", example = "LISTING")
    AdminAuditObjectType objectType,

    @Schema(description = "Id of the affected resource — numeric for listings/users, the source "
        + "code for sources", example = "42")
    String objectId,

    @Schema(description = "When the action was performed", example = "2026-08-18T14:20:00Z")
    Instant createdAt
) {}
