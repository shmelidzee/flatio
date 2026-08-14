package com.flatio.repository;

import com.flatio.domain.source.SyncRun;
import com.flatio.domain.source.SyncRunStatus;
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
   * Deletes all but the {@code keep} most recently started runs for the given source.
   *
   * <p>Used by the scheduled cleanup job to bound sync run history per source.
   *
   * @param sourceId the connector source identifier
   * @param keep     number of most recent runs to retain
   * @return number of deleted rows
   */
  @Modifying
  @Query(
      value = "DELETE FROM sync_runs WHERE source_id = :sourceId AND id NOT IN "
          + "(SELECT id FROM sync_runs WHERE source_id = :sourceId ORDER BY started_at DESC LIMIT :keep)",
      nativeQuery = true
  )
  int deleteOldRunsBeyondLimit(@Param("sourceId") String sourceId, @Param("keep") int keep);
}
