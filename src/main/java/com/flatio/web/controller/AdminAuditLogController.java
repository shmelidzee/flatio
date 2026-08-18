package com.flatio.web.controller;

import com.flatio.service.AdminAuditLogService;
import com.flatio.web.dto.AdminAuditLogResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin REST controller for the admin action audit log feed.
 */
@RestController
@RequestMapping("/api/v1/admin/audit-log")
@Tag(name = "Admin — Audit Log", description = "Read-only feed of recent admin actions")
@RequiredArgsConstructor
public class AdminAuditLogController {

  private final AdminAuditLogService adminAuditLogService;

  /**
   * Returns a paginated feed of recent admin actions, newest first.
   *
   * @param pageable pagination and sorting (default: 20 per page, sorted by createdAt DESC)
   * @return page of matching audit log entries
   */
  @Operation(
      summary = "List recent admin actions",
      description = "Returns a paginated feed of recorded admin actions (status changes, role "
          + "changes, source reconfiguration) — who did what, to which resource, and when."
  )
  @ApiResponse(responseCode = "200", description = "Audit log page returned")
  @ApiResponse(responseCode = "401", description = "Unauthorized")
  @ApiResponse(responseCode = "403", description = "Caller does not have the ADMIN role")
  @GetMapping
  public Page<AdminAuditLogResponse> findRecent(
      @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable
  ) {
    return adminAuditLogService.findRecent(pageable);
  }
}
