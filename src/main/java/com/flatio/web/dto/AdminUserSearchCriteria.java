package com.flatio.web.dto;

import com.flatio.domain.user.UserRole;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Search criteria for {@code GET /api/v1/admin/users}.
 *
 * <p>Bound from HTTP query parameters via {@code @ModelAttribute}.
 */
@Schema(description = "Filter parameters for the admin user search")
public record AdminUserSearchCriteria(
    @Schema(description = "Role filter", example = "ADMIN")
    UserRole role,

    @Schema(description = "Active status filter", example = "true")
    Boolean active
) {}
