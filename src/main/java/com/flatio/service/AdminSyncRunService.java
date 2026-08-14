package com.flatio.service;

import com.flatio.web.dto.AdminSyncRunResponse;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Admin service for browsing connector sync run history.
 */
public interface AdminSyncRunService {

  /**
   * Returns a page of sync runs, optionally filtered by source, newest first.
   *
   * @param sourceId source code filter, or null to return runs across all sources
   * @param pageable pagination configuration
   * @return page of matching runs, never null
   */
  Page<AdminSyncRunResponse> search(String sourceId, Pageable pageable);

  /**
   * Returns the most recent sync run for each registered source.
   *
   * @return list of latest runs, one per source that has run at least once, never null
   */
  List<AdminSyncRunResponse> findLatestPerSource();
}
