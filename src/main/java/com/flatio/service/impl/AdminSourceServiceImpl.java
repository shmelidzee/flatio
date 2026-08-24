package com.flatio.service.impl;

import com.flatio.common.exception.SourceNotFoundException;
import com.flatio.domain.audit.AdminAuditObjectType;
import com.flatio.domain.source.Source;
import com.flatio.repository.SourceRepository;
import com.flatio.repository.SyncRunRepository;
import com.flatio.service.AdminAuditLogService;
import com.flatio.service.AdminSourceService;
import com.flatio.service.SyncRunService;
import com.flatio.web.dto.AdminSourceResponse;
import com.flatio.web.dto.AdminSourceUpdateRequest;
import com.flatio.web.mapper.AdminSourceMapper;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@Slf4j
@RequiredArgsConstructor
public class AdminSourceServiceImpl implements AdminSourceService {

  private final SourceRepository sourceRepository;
  private final SyncRunRepository syncRunRepository;
  private final SyncRunService syncRunService;
  private final AdminSourceMapper adminSourceMapper;
  private final AdminAuditLogService adminAuditLogService;

  @Override
  public List<AdminSourceResponse> findAll() {
    Map<String, Instant> lastSyncBySource = lastSuccessfulSyncBySource();
    return sourceRepository.findAll(Sort.by(Sort.Direction.ASC, "code")).stream()
        .map(source -> adminSourceMapper.toResponse(source, lastSyncBySource.get(source.getCode())))
        .toList();
  }

  @Override
  @Transactional
  public AdminSourceResponse update(String sourceId, AdminSourceUpdateRequest request, Long adminId) {
    Source source = sourceRepository.findByCode(sourceId)
        .orElseThrow(() -> new SourceNotFoundException(sourceId));

    if (request.enabled() != null) {
      source.setActive(request.enabled());
    }
    sourceRepository.save(source);
    log.info("Admin action: action=updateSource, sourceId={}, enabled={}, adminId={}",
        sourceId, source.isActive(), adminId);
    adminAuditLogService.record("updateSource", AdminAuditObjectType.SOURCE, sourceId, adminId);

    return adminSourceMapper.toResponse(
        source, syncRunService.findLastSuccessfulRunAt(sourceId).orElse(null));
  }

  /**
   * Fetches the last successful sync time for every source in a single query.
   *
   * @return map of source code to last successful finish time, never null
   */
  private Map<String, Instant> lastSuccessfulSyncBySource() {
    Map<String, Instant> result = new HashMap<>();
    for (Object[] row : syncRunRepository.findLastSuccessfulFinishedAtGroupedBySource()) {
      result.put((String) row[0], (Instant) row[1]);
    }
    return result;
  }
}
