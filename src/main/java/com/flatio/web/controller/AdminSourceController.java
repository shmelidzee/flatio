package com.flatio.web.controller;

import com.flatio.service.AdminSourceService;
import com.flatio.service.AdminSyncRunService;
import com.flatio.web.dto.AdminSourceResponse;
import com.flatio.web.dto.AdminSourceUpdateRequest;
import com.flatio.web.dto.AdminSyncRunResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin REST controller for source management and sync run monitoring.
 */
@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "Admin — Sources", description = "Manage data sources and inspect connector sync run history")
@RequiredArgsConstructor
public class AdminSourceController {

  private final AdminSourceService adminSourceService;
  private final AdminSyncRunService adminSyncRunService;

  /**
   * Returns every registered source with its enabled state, sync interval, and last sync time.
   *
   * @return list of sources
   */
  @Operation(
      summary = "List sources",
      description = "Returns every registered data source with its status, sync interval, and last successful sync time."
  )
  @ApiResponse(responseCode = "200", description = "Sources returned")
  @ApiResponse(responseCode = "401", description = "Unauthorized")
  @ApiResponse(responseCode = "403", description = "Caller does not have the ADMIN role")
  @GetMapping("/sources")
  public List<AdminSourceResponse> findSources() {
    return adminSourceService.findAll();
  }

  /**
   * Enables, disables, or reconfigures the sync interval of a source.
   *
   * @param sourceId       the source code to update
   * @param request        fields to change; null fields are left unchanged
   * @param authentication the authenticated admin, used for audit logging
   * @return the updated source
   */
  @Operation(
      summary = "Update a source",
      description = "Enables/disables a source and/or changes its configured sync interval. "
          + "Disabled sources are skipped by all scheduled sync jobs."
  )
  @ApiResponse(responseCode = "200", description = "Source updated")
  @ApiResponse(responseCode = "400", description = "Invalid request body")
  @ApiResponse(responseCode = "401", description = "Unauthorized")
  @ApiResponse(responseCode = "403", description = "Caller does not have the ADMIN role")
  @ApiResponse(responseCode = "404", description = "Source not found")
  @PatchMapping("/sources/{sourceId}")
  public AdminSourceResponse updateSource(
      @PathVariable String sourceId,
      @Valid @RequestBody AdminSourceUpdateRequest request,
      Authentication authentication
  ) {
    return adminSourceService.update(sourceId, request, Long.parseLong(authentication.getName()));
  }

  /**
   * Returns a paginated list of sync runs, optionally filtered by source.
   *
   * @param sourceId source code filter; omit to return runs across all sources
   * @param pageable pagination and sorting (default: 20 per page)
   * @return page of matching sync runs
   */
  @Operation(
      summary = "List sync runs",
      description = "Returns a paginated history of connector sync runs, newest first. "
          + "Filter by sourceId to see runs for a single source."
  )
  @ApiResponse(responseCode = "200", description = "Sync runs page returned")
  @ApiResponse(responseCode = "401", description = "Unauthorized")
  @ApiResponse(responseCode = "403", description = "Caller does not have the ADMIN role")
  @GetMapping("/sync-runs")
  public Page<AdminSyncRunResponse> findSyncRuns(
      @Parameter(description = "Source code filter, e.g. onliner") @RequestParam(required = false) String sourceId,
      @PageableDefault(size = 20, sort = "startedAt", direction = Sort.Direction.DESC) Pageable pageable
  ) {
    return adminSyncRunService.search(sourceId, pageable);
  }

  /**
   * Returns the most recent sync run for each registered source.
   *
   * @return list of latest runs, one per source that has run at least once
   */
  @Operation(
      summary = "Get latest sync run per source",
      description = "Returns the most recent sync run — successful or failed — for each registered source."
  )
  @ApiResponse(responseCode = "200", description = "Latest sync runs returned")
  @ApiResponse(responseCode = "401", description = "Unauthorized")
  @ApiResponse(responseCode = "403", description = "Caller does not have the ADMIN role")
  @GetMapping("/sync-runs/latest")
  public List<AdminSyncRunResponse> findLatestSyncRuns() {
    return adminSyncRunService.findLatestPerSource();
  }
}
