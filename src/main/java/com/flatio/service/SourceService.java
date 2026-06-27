package com.flatio.service;

import com.flatio.domain.source.Source;

/**
 * Business service for managing listing sources.
 */
public interface SourceService {

  /**
   * Finds a registered listing source by its unique code.
   *
   * @param code the source identifier (e.g., "REALT_SALE", "ONLINER")
   * @return the matching Source entity
   * @throws IllegalStateException if no source with the given code is registered in the database
   */
  Source findByCodeOrThrow(String code);
}
