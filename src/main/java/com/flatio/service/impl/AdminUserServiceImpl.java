package com.flatio.service.impl;

import com.flatio.common.exception.SelfRoleChangeForbiddenException;
import com.flatio.common.exception.UserNotFoundException;
import com.flatio.domain.audit.AdminAuditObjectType;
import com.flatio.domain.user.User;
import com.flatio.domain.user.UserRole;
import com.flatio.repository.UserRepository;
import com.flatio.security.UserStatusCache;
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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@Transactional(readOnly = true)
@Slf4j
@RequiredArgsConstructor
public class AdminUserServiceImpl implements AdminUserService {

  private final UserRepository userRepository;
  private final AdminUserMapper adminUserMapper;
  private final AdminAuditLogService adminAuditLogService;
  private final UserStatusCache userStatusCache;

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
    // Issue #365: evict so the new active/role state applies on the user's next request
    // instead of waiting out UserStatusCache's TTL.
    evictStatusCacheAfterCommit(id);
    log.info("Admin action: action=updateUser, userId={}, active={}, role={}, adminId={}",
        id, user.isActive(), user.getRole(), currentAdminId);
    adminAuditLogService.record("updateUser", AdminAuditObjectType.USER, String.valueOf(id), currentAdminId);

    return adminUserMapper.toResponse(user);
  }

  /**
   * Evicts the user's cached status only once this transaction commits.
   *
   * <p>Evicting immediately, before commit, leaves a window where a concurrent request can hit
   * {@link UserStatusCache}, miss, and re-read the still-uncommitted (pre-update) row straight
   * from the database — re-caching the stale state for a fresh TTL and defeating the fast
   * revocation this cache exists for.
   *
   * <p>Falls back to an immediate evict when no Spring transaction is actually active (e.g. a
   * unit test invoking this service directly, bypassing the {@code @Transactional} proxy) —
   * there is no pending commit to wait for in that case.
   *
   * @param id the updated user's id
   */
  private void evictStatusCacheAfterCommit(Long id) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      userStatusCache.evict(id);
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
      @Override
      public void afterCommit() {
        userStatusCache.evict(id);
      }
    });
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
