package com.flatio.service.impl;

import com.flatio.domain.source.SyncRun;
import com.flatio.domain.source.SyncRunStatus;
import com.flatio.repository.SyncRunRepository;
import com.flatio.service.SyncRunService;
import com.flatio.service.domain.SyncRunRequest;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@Slf4j
@RequiredArgsConstructor
public class SyncRunServiceImpl implements SyncRunService {

  private final SyncRunRepository syncRunRepository;

  @Override
  @Transactional
  public void record(SyncRunRequest request) {
    SyncRun run = new SyncRun();
    run.setSourceId(request.sourceId());
    run.setSyncType(request.syncType());
    run.setStatus(request.status());
    run.setStartedAt(request.startedAt());
    run.setFinishedAt(request.finishedAt());
    run.setListingsFetched(request.listingsFetched());
    run.setListingsAdded(request.listingsAdded());
    run.setListingsUpdated(request.listingsUpdated());
    run.setListingsErrors(request.listingsErrors());
    syncRunRepository.save(run);
    log.debug("Sync run recorded: source={}, type={}, status={}, finishedAt={}",
        request.sourceId(), request.syncType(), request.status(), request.finishedAt());
  }

  @Override
  public Optional<Instant> findLastSuccessfulRunAt() {
    return syncRunRepository
        .findTopByStatusOrderByFinishedAtDesc(SyncRunStatus.SUCCESS)
        .map(SyncRun::getFinishedAt);
  }

  @Override
  public Optional<Instant> findLastSuccessfulRunAt(String sourceId) {
    return syncRunRepository
        .findTopBySourceIdAndStatusOrderByFinishedAtDesc(sourceId, SyncRunStatus.SUCCESS)
        .map(SyncRun::getFinishedAt);
  }
}
