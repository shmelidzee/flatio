package com.flatio.web.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.flatio.domain.listing.ListingStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "Admin request to change a listing's status")
public record AdminListingUpdateRequest(
    @Schema(description = "New listing status", example = "INACTIVE", requiredMode = REQUIRED)
    @NotNull ListingStatus status
) {}
