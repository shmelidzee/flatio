package com.flatio.service;

import com.flatio.web.dto.AdminUserResponse;
import com.flatio.web.dto.AdminUserSearchCriteria;
import com.flatio.web.dto.AdminUserUpdateRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Admin service for viewing and managing platform users.
 */
public interface AdminUserService {

  /**
   * Returns a page of users matching the given filter criteria.
   *
   * @param criteria filter parameters (role, active); null fields match any value
   * @param pageable pagination and sorting configuration
   * @return page of matching users, never null
   */
  Page<AdminUserResponse> search(AdminUserSearchCriteria criteria, Pageable pageable);

  /**
   * Updates a user's active state and/or role.
   *
   * <p>Only non-null fields in the request are applied.
   *
   * @param id             the user id to update
   * @param request        fields to change; null fields are left unchanged
   * @param currentAdminId id of the authenticated admin performing the change, used for audit
   *                       logging and to block an admin from downgrading their own role
   * @return the updated user
   * @throws com.flatio.common.exception.UserNotFoundException          if no user with the given id exists
   * @throws com.flatio.common.exception.SelfRoleChangeForbiddenException if the caller tries to
   *                                                                       change their own role away from ADMIN
   */
  AdminUserResponse update(Long id, AdminUserUpdateRequest request, Long currentAdminId);
}
