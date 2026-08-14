package com.flatio.service.impl;

import com.flatio.domain.source.SyncRun;
import com.flatio.repository.SourceRepository;
import com.flatio.repository.SyncRunRepository;
import com.flatio.service.AdminSyncRunService;
import com.flatio.web.dto.AdminSyncRunResponse;
import com.flatio.web.mapper.AdminSyncRunMapper;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class AdminSyncRunServiceImpl implements AdminSyncRunService {

  private final SyncRunRepository syncRunRepository;
  private final SourceRepository sourceRepository;
  private final AdminSyncRunMapper adminSyncRunMapper;

  @Override
  public Page<AdminSyncRunResponse> search(String sourceId, Pageable pageable) {
    Page<SyncRun> runs = sourceId != null
        ? syncRunRepository.findBySourceIdOrderByStartedAtDesc(sourceId, pageable)
        : syncRunRepository.findAllByOrderByStartedAtDesc(pageable);
    return runs.map(adminSyncRunMapper::toResponse);
  }

  @Override
  public List<AdminSyncRunResponse> findLatestPerSource() {
    return sourceRepository.findAll().stream()
        .map(source -> syncRunRepository.findTopBySourceIdOrderByStartedAtDesc(source.getCode()))
        .flatMap(Optional::stream)
        .map(adminSyncRunMapper::toResponse)
        .toList();
  }
}
