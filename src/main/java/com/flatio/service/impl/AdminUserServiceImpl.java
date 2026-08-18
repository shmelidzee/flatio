package com.flatio.service.impl;

import com.flatio.common.exception.SelfRoleChangeForbiddenException;
import com.flatio.common.exception.UserNotFoundException;
import com.flatio.domain.audit.AdminAuditObjectType;
import com.flatio.domain.user.User;
import com.flatio.domain.user.UserRole;
import com.flatio.repository.UserRepository;
import com.flatio.service.AdminAuditLogService;
import com.flatio.service.AdminUserService;
import com.flatio.web.dto.AdminUserResponse;
import com.flatio.web.dto.AdminUserSearchCriteria;
import com.flatio.web.dto.AdminUserUpdateRequest;
import com.flatio.web.mapper.AdminUserMapper;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@Slf4j
@RequiredArgsConstructor
public class AdminUserServiceImpl implements AdminUserService {

  private final UserRepository userRepository;
  private final AdminUserMapper adminUserMapper;
  private final AdminAuditLogService adminAuditLogService;

  @Override
  public Page<AdminUserResponse> search(AdminUserSearchCriteria criteria, Pageable pageable) {
    log.debug("Admin searching users with criteria={}", criteria);
    return userRepository.findAll(buildSearchSpec(criteria), pageable)
        .map(adminUserMapper::toResponse);
  }

  @Override
  @Transactional
  public AdminUserResponse update(Long id, AdminUserUpdateRequest request, Long currentAdminId) {
    User user = userRepository.findById(id)
        .orElseThrow(() -> new UserNotFoundException(id));

    if (request.role() != null) {
      validateNotSelfDowngrade(user, request.role(), currentAdminId);
      user.setRole(request.role());
    }
    if (request.active() != null) {
      user.setActive(request.active());
    }
    userRepository.save(user);
    log.info("Admin action: action=updateUser, userId={}, active={}, role={}, adminId={}",
        id, user.isActive(), user.getRole(), currentAdminId);
    adminAuditLogService.record("updateUser", AdminAuditObjectType.USER, String.valueOf(id), currentAdminId);

    return adminUserMapper.toResponse(user);
  }

  /**
   * Blocks an admin from changing their own role away from ADMIN, so the last admin cannot
   * accidentally lock themselves out of the admin panel.
   *
   * @param user           the user being updated, before the new role is applied
   * @param newRole        the role requested by the update
   * @param currentAdminId id of the authenticated admin performing the change
   */
  private void validateNotSelfDowngrade(User user, UserRole newRole, Long currentAdminId) {
    boolean isSelf = user.getId().equals(currentAdminId);
    boolean wasAdmin = user.getRole() == UserRole.ADMIN;
    boolean isDowngrade = newRole != UserRole.ADMIN;
    if (isSelf && wasAdmin && isDowngrade) {
      throw new SelfRoleChangeForbiddenException(currentAdminId);
    }
  }

  private Specification<User> buildSearchSpec(AdminUserSearchCriteria criteria) {
    return (root, query, cb) -> {
      List<Predicate> predicates = new ArrayList<>();
      if (criteria.role() != null) {
        predicates.add(cb.equal(root.get("role"), criteria.role()));
      }
      if (criteria.active() != null) {
        predicates.add(cb.equal(root.get("active"), criteria.active()));
      }
      return cb.and(predicates.toArray(new Predicate[0]));
    };
  }
}
