package com.flatio.service;

import com.flatio.service.domain.SyncRunRequest;
import java.time.Instant;
import java.util.Optional;

/**
 * Service for recording and querying connector sync run history.
 */
public interface SyncRunService {

  /**
   * Persists a completed sync run record.
   *
   * @param request the run details to store; must not be null
   */
  void record(SyncRunRequest request);

  /**
   * Returns the finish timestamp of the most recent successful sync across all sources.
   *
   * @return the latest successful finish time, or empty if no successful run exists
   */
  Optional<Instant> findLastSuccessfulRunAt();
}
