package com.flatio.service;

import com.flatio.web.dto.AdminSourceResponse;
import com.flatio.web.dto.AdminSourceUpdateRequest;
import java.util.List;

/**
 * Admin service for viewing and configuring data sources.
 */
public interface AdminSourceService {

  /**
   * Returns all registered sources with their current status and last sync time.
   *
   * @return list of sources, never null
   */
  List<AdminSourceResponse> findAll();

  /**
   * Updates a source's enabled state and/or sync interval.
   *
   * <p>Only non-null fields in the request are applied.
   *
   * @param sourceId the source code to update
   * @param request  fields to change; null fields are left unchanged
   * @param adminId  id of the authenticated admin performing the change, used for audit logging
   * @return the updated source
   * @throws com.flatio.common.exception.SourceNotFoundException if no source with the given code exists
   */
  AdminSourceResponse update(String sourceId, AdminSourceUpdateRequest request, Long adminId);
}
