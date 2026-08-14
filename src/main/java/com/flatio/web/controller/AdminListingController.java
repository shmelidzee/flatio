package com.flatio.web.controller;

import com.flatio.service.AdminListingService;
import com.flatio.web.dto.AdminListingSearchCriteria;
import com.flatio.web.dto.AdminListingUpdateRequest;
import com.flatio.web.dto.ListingSummaryResponse;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin REST controller for listing search and manual moderation.
 */
@RestController
@RequestMapping("/api/v1/admin/listings")
@Tag(name = "Admin — Listings", description = "Search listings across all statuses and manually moderate them")
@RequiredArgsConstructor
public class AdminListingController {

  private final AdminListingService adminListingService;

  /**
   * Returns a paginated list of listings matching the given admin filter criteria.
   *
   * <p>Unlike the public search, all statuses are included when {@code status} is omitted.
   *
   * @param criteria filter parameters bound from query string
   * @param pageable pagination and sorting (default: 20 per page, sorted by createdAt DESC)
   * @return page of matching listing summaries
   */
  @Operation(
      summary = "Search listings for moderation",
      description = "Returns a paginated list of listings matching the filter criteria, across all "
          + "statuses unless status is specified. Set duplicatesOnly=true to see listings that "
          + "share a deduplication hash with another listing."
  )
  @ApiResponse(responseCode = "200", description = "Listings page returned")
  @ApiResponse(responseCode = "400", description = "Invalid filter parameters")
  @ApiResponse(responseCode = "401", description = "Unauthorized")
  @ApiResponse(responseCode = "403", description = "Caller does not have the ADMIN role")
  @GetMapping
  public Page<ListingSummaryResponse> search(
      @ModelAttribute AdminListingSearchCriteria criteria,
      @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable
  ) {
    return adminListingService.search(criteria, pageable);
  }

  /**
   * Manually changes a listing's status, e.g. to deactivate it.
   *
   * @param id             the listing ID
   * @param request        the new status
   * @param authentication the authenticated admin, used for audit logging
   * @return the updated listing summary
   */
  @Operation(
      summary = "Update listing status",
      description = "Manually changes a listing's status, e.g. to deactivate a listing the source "
          + "connector has not yet retired."
  )
  @ApiResponse(responseCode = "200", description = "Listing status updated")
  @ApiResponse(responseCode = "400", description = "Invalid request body")
  @ApiResponse(responseCode = "401", description = "Unauthorized")
  @ApiResponse(responseCode = "403", description = "Caller does not have the ADMIN role")
  @ApiResponse(responseCode = "404", description = "Listing not found")
  @PatchMapping("/{id}")
  public ListingSummaryResponse updateStatus(
      @PathVariable Long id,
      @Valid @RequestBody AdminListingUpdateRequest request,
      Authentication authentication
  ) {
    return adminListingService.updateStatus(id, request.status(), authentication.getName());
  }

  /**
   * Removes a listing from its duplicate group.
   *
   * @param id             the listing ID
   * @param authentication the authenticated admin, used for audit logging
   */
  @Operation(
      summary = "Unlink a listing from its duplicate group",
      description = "Clears the listing's deduplication hash so it is no longer grouped with "
          + "listings the deduplication process considered the same property. Other listings in "
          + "the group are left unchanged."
  )
  @ApiResponse(responseCode = "200", description = "Listing unlinked from its duplicate group")
  @ApiResponse(responseCode = "401", description = "Unauthorized")
  @ApiResponse(responseCode = "403", description = "Caller does not have the ADMIN role")
  @ApiResponse(responseCode = "404", description = "Listing not found")
  @DeleteMapping("/{id}/duplicate-group")
  public void unlinkDuplicateGroup(@PathVariable Long id, Authentication authentication) {
    adminListingService.unlinkDuplicateGroup(id, authentication.getName());
  }
}
