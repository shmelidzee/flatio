package com.flatio.repository;

import com.flatio.domain.source.SyncRun;
import com.flatio.domain.source.SyncRunStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SyncRunRepository extends JpaRepository<SyncRun, Long> {

  /**
   * Returns the most recently finished sync run with the given status across all sources.
   *
   * @param status the desired terminal status (e.g. SUCCESS)
   * @return the latest matching run, or empty if none exists
   */
  Optional<SyncRun> findTopByStatusOrderByFinishedAtDesc(SyncRunStatus status);

  /**
   * Returns the most recently finished sync run for the given source with the given status.
   *
   * @param sourceId the connector source identifier
   * @param status   the desired terminal status (e.g. SUCCESS)
   * @return the latest matching run for the source, or empty if none exists
   */
  Optional<SyncRun> findTopBySourceIdAndStatusOrderByFinishedAtDesc(String sourceId, SyncRunStatus status);

  /**
   * Returns the most recently started sync run for the given source, regardless of outcome.
   *
   * <p>Used by the admin dashboard to show the latest run per source, including failures.
   *
   * @param sourceId the connector source identifier
   * @return the latest run for the source, or empty if none exists
   */
  Optional<SyncRun> findTopBySourceIdOrderByStartedAtDesc(String sourceId);

  /**
   * Returns the most recently started sync run for every source that has run at least once,
   * regardless of outcome, in a single query.
   *
   * <p>Used by the admin dashboard instead of looping {@link #findTopBySourceIdOrderByStartedAtDesc}
   * per source, which would issue one query per registered source.
   *
   * @return one run per distinct source, never null
   */
  @Query(
      value = "SELECT DISTINCT ON (source_id) id, source_id, sync_type, status, started_at, finished_at, "
          + "listings_fetched, listings_added, listings_updated, listings_errors, created_at, updated_at "
          + "FROM sync_runs ORDER BY source_id, started_at DESC",
      nativeQuery = true
  )
  List<SyncRun> findLatestPerSource();

  /**
   * Returns the finish time of the most recent SUCCESS run for every source, in a single query.
   *
   * <p>Used by the admin sources list instead of looping {@code findLastSuccessfulRunAt} per
   * source, which would issue one query per registered source.
   *
   * @return array of {@code [sourceId, finishedAt]} pairs, one per source with at least one
   *     successful run; never null
   */
  @Query("SELECT s.sourceId, MAX(s.finishedAt) FROM SyncRun s "
      + "WHERE s.status = com.flatio.domain.source.SyncRunStatus.SUCCESS GROUP BY s.sourceId")
  List<Object[]> findLastSuccessfulFinishedAtGroupedBySource();

  /**
   * Returns a page of sync runs for the given source, newest first.
   *
   * @param sourceId the connector source identifier
   * @param pageable pagination configuration
   * @return page of matching runs, never null
   */
  Page<SyncRun> findBySourceIdOrderByStartedAtDesc(String sourceId, Pageable pageable);

  /**
   * Returns a page of sync runs across all sources, newest first.
   *
   * @param pageable pagination configuration
   * @return page of runs, never null
   */
  Page<SyncRun> findAllByOrderByStartedAtDesc(Pageable pageable);

  /**
   * Deletes all but the {@code keep} most recently started runs for every source, in one query.
   *
   * <p>Used by the scheduled cleanup job to bound sync run history per source, without looping
   * over each registered source individually.
   *
   * @param keep number of most recent runs to retain per source
   * @return number of deleted rows
   */
  @Modifying
  @Query(
      value = "DELETE FROM sync_runs WHERE id IN ("
          + "SELECT id FROM ("
          + "  SELECT id, ROW_NUMBER() OVER (PARTITION BY source_id ORDER BY started_at DESC) AS rn "
          + "  FROM sync_runs"
          + ") ranked WHERE rn > :keep)",
      nativeQuery = true
  )
  int deleteOldRunsBeyondLimitForAllSources(@Param("keep") int keep);
}
