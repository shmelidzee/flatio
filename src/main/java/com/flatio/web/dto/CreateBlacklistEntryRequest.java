package com.flatio.web.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.flatio.domain.blacklist.BlacklistEntryType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request body for adding an entry to the authenticated user's blacklist.
 *
 * <p>{@code value}'s expected format depends on {@code type}: a valid numeric listing ID for
 * {@code LISTING}, a valid source {@code code} (e.g. {@code "onliner"}) for {@code SOURCE}, or a
 * non-blank stop-word (at most 100 characters) for {@code KEYWORD}. This type-dependent format is
 * validated in the service layer, since it cannot be expressed with per-field bean validation
 * constraints alone.
 */
@Schema(description = "Add a blacklist entry request")
public record CreateBlacklistEntryRequest(
    @Schema(description = "Kind of item to blacklist", example = "KEYWORD", requiredMode = REQUIRED)
    @NotNull BlacklistEntryType type,

    @Schema(description = "Listing ID (LISTING), source code (SOURCE), or stop-word (KEYWORD)",
        example = "novostroyka", requiredMode = REQUIRED)
    @NotBlank @Size(max = 100) String value
) {}
