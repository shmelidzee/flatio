package com.flatio.service;

import com.flatio.domain.audit.AdminAuditObjectType;
import com.flatio.web.dto.AdminAuditLogResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Service for recording and reading the admin action audit log.
 */
public interface AdminAuditLogService {

  /**
   * Records a single admin action.
   *
   * @param action     short action code, matches the {@code action=} tag already used in the
   *                   SLF4J "Admin action: ..." log lines (e.g. {@code updateListingStatus})
   * @param objectType type of resource the action was performed on
   * @param objectId   id of the affected resource — numeric id for listings/users, source code
   *                   for sources
   * @param adminId    id of the admin who performed the action
   */
  void record(String action, AdminAuditObjectType objectType, String objectId, Long adminId);

  /**
   * Returns a page of recorded admin actions, newest first, enriched with the acting admin's
   * display name.
   *
   * @param pageable pagination and sorting configuration
   * @return page of audit log entries, never null
   */
  Page<AdminAuditLogResponse> findRecent(Pageable pageable);
}
