package com.flatio.web.controller;

import com.flatio.service.AdminUserService;
import com.flatio.web.dto.AdminUserResponse;
import com.flatio.web.dto.AdminUserSearchCriteria;
import com.flatio.web.dto.AdminUserUpdateRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin REST controller for user search and management.
 */
@RestController
@RequestMapping("/api/v1/admin/users")
@Tag(name = "Admin — Users", description = "Search platform users and manage their active state and role")
@RequiredArgsConstructor
public class AdminUserController {

  private final AdminUserService adminUserService;

  /**
   * Returns a paginated list of users matching the given filter criteria.
   *
   * @param criteria filter parameters bound from query string (role, active)
   * @param pageable pagination and sorting (default: 20 per page, sorted by createdAt DESC)
   * @return page of matching users
   */
  @Operation(
      summary = "Search users",
      description = "Returns a paginated list of platform users. Filter by role and/or active state; "
          + "omit either to match any value."
  )
  @ApiResponse(responseCode = "200", description = "Users page returned")
  @ApiResponse(responseCode = "401", description = "Unauthorized")
  @ApiResponse(responseCode = "403", description = "Caller does not have the ADMIN role")
  @GetMapping
  public Page<AdminUserResponse> search(
      @ModelAttribute AdminUserSearchCriteria criteria,
      @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable
  ) {
    return adminUserService.search(criteria, pageable);
  }

  /**
   * Deactivates a user and/or changes their role.
   *
   * @param id             the user id to update
   * @param request        fields to change; null fields are left unchanged
   * @param authentication the authenticated admin, used for audit logging and self-downgrade protection
   * @return the updated user
   */
  @Operation(
      summary = "Update a user",
      description = "Deactivates/reactivates a user and/or changes their role. An admin cannot "
          + "change their own role away from ADMIN, to avoid locking themselves out."
  )
  @ApiResponse(responseCode = "200", description = "User updated")
  @ApiResponse(responseCode = "400", description = "Invalid request body")
  @ApiResponse(responseCode = "401", description = "Unauthorized")
  @ApiResponse(responseCode = "403",
      description = "Caller does not have the ADMIN role, or attempted to change their own role away from ADMIN")
  @ApiResponse(responseCode = "404", description = "User not found")
  @PatchMapping("/{id}")
  public AdminUserResponse update(
      @PathVariable Long id,
      @Valid @RequestBody AdminUserUpdateRequest request,
      Authentication authentication
  ) {
    return adminUserService.update(id, request, Long.parseLong(authentication.getName()));
  }
}
