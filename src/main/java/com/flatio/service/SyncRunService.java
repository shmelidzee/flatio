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

  /**
   * Returns the finish timestamp of the most recent successful sync for the given source.
   *
   * @param sourceId connector source identifier
   * @return the latest successful finish time for that source, or empty if none exists
   */
  Optional<Instant> findLastSuccessfulRunAt(String sourceId);

  /**
   * Checks whether the given source has ever recorded a sync run, of any outcome.
   *
   * <p>Used to distinguish "just added, hasn't had a chance to run yet" from "has been trying
   * and failing" when evaluating the source-health alert rules (issue #419) — a brand new source
   * must not trigger a false "no successful sync" alert before its first scheduled run.
   *
   * @param sourceId connector source identifier
   * @return true if at least one run (success or failure) has been recorded
   */
  boolean hasAnyRun(String sourceId);

  /**
   * Computes the failure rate over the most recent {@code windowSize} runs for a source.
   *
   * @param sourceId   connector source identifier
   * @param windowSize maximum number of most recent runs to consider, newest first
   * @return the fraction of those runs with {@code FAILURE} status, or empty if the source has
   *     no runs recorded yet
   */
  Optional<Double> calculateRecentFailureRate(String sourceId, int windowSize);

  /**
   * Deletes old sync run records, keeping only the most recent {@code keepPerSource}
   * runs for each registered source.
   *
   * @param keepPerSource number of most recent runs to retain per source
   */
  void cleanupOldRuns(int keepPerSource);
}
