package com.flatio.repository;

import com.flatio.domain.source.SyncRun;
import com.flatio.domain.source.SyncRunStatus;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SyncRunRepository extends JpaRepository<SyncRun, Long> {

  /**
   * Returns the most recently finished sync run with the given status.
   *
   * @param status the desired terminal status (e.g. SUCCESS)
   * @return the latest matching run, or empty if none exists
   */
  Optional<SyncRun> findTopByStatusOrderByFinishedAtDesc(SyncRunStatus status);
}
