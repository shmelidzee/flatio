package com.flatio.web.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * Request body for adding a listing to the authenticated user's favorites.
 */
@Schema(description = "Add a listing to favorites request")
public record CreateFavoriteRequest(
    @Schema(description = "ID of the listing to add to favorites", example = "42", requiredMode = REQUIRED)
    @NotNull Long listingId
) {}
