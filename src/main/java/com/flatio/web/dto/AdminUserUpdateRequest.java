package com.flatio.web.dto;

import com.flatio.domain.user.UserRole;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Partial update for a user's active state and/or role; "
    + "omitted fields are left unchanged")
public record AdminUserUpdateRequest(
    @Schema(description = "When false, deactivates the user", example = "false")
    Boolean active,

    @Schema(description = "New role to assign", example = "PRO")
    UserRole role
) {}
