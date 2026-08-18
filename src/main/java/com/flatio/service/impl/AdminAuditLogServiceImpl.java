package com.flatio.service.impl;

import com.flatio.domain.audit.AdminAuditLog;
import com.flatio.domain.audit.AdminAuditObjectType;
import com.flatio.domain.user.User;
import com.flatio.repository.AdminAuditLogRepository;
import com.flatio.repository.UserRepository;
import com.flatio.service.AdminAuditLogService;
import com.flatio.web.dto.AdminAuditLogResponse;
import com.flatio.web.mapper.AdminAuditLogMapper;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@Slf4j
@RequiredArgsConstructor
public class AdminAuditLogServiceImpl implements AdminAuditLogService {

  private final AdminAuditLogRepository adminAuditLogRepository;
  private final UserRepository userRepository;
  private final AdminAuditLogMapper adminAuditLogMapper;

  @Override
  @Transactional
  public void record(String action, AdminAuditObjectType objectType, String objectId, Long adminId) {
    var entry = new AdminAuditLog();
    entry.setAction(action);
    entry.setObjectType(objectType);
    entry.setObjectId(objectId);
    entry.setAdminId(adminId);
    adminAuditLogRepository.save(entry);
  }

  @Override
  public Page<AdminAuditLogResponse> findRecent(Pageable pageable) {
    Page<AdminAuditLog> page = adminAuditLogRepository.findAll(pageable);
    Map<Long, String> displayNameByAdminId = displayNamesFor(page.getContent());
    return page.map(entry -> adminAuditLogMapper.toResponse(entry, displayNameByAdminId.get(entry.getAdminId())));
  }

  /**
   * Fetches display names for every distinct admin in the given page in a single query.
   *
   * @param entries the page's audit log entries
   * @return map of admin id to display name; entries with no matching user are simply absent
   */
  private Map<Long, String> displayNamesFor(List<AdminAuditLog> entries) {
    List<Long> adminIds = entries.stream()
        .map(AdminAuditLog::getAdminId)
        .distinct()
        .toList();
    return userRepository.findAllById(adminIds).stream()
        .collect(Collectors.toMap(User::getId, User::getDisplayName));
  }
}
