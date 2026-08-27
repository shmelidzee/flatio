package com.flatio.service;

import com.flatio.domain.blacklist.BlacklistEntryType;
import com.flatio.web.dto.BlacklistEntryResponse;
import com.flatio.web.dto.CreateBlacklistEntryRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Service for managing the listings, sources, and stop-words a user has blacklisted.
 */
public interface BlacklistService {

  /**
   * Adds an entry to the given user's blacklist.
   *
   * <p>Re-adding an entry that already exists (same type and value) is idempotent: the existing
   * entry is returned unchanged and does not count against the tariff limit again.
   *
   * @param userId  the owning user's ID
   * @param request the entry to blacklist
   * @return the created (or already existing) blacklist entry
   * @throws com.flatio.common.exception.BlacklistInvalidValueException if {@code value} does not
   *     match the format required by {@code type}
   * @throws com.flatio.common.exception.ListingNotFoundException if {@code type} is
   *     {@code LISTING} and the referenced listing does not exist
   * @throws com.flatio.common.exception.SourceNotFoundException if {@code type} is {@code SOURCE}
   *     and the referenced source does not exist
   * @throws com.flatio.common.exception.BlacklistKeywordLimitExceededException if {@code type} is
   *     {@code KEYWORD} and the user has reached their tariff's stop-word limit
   */
  BlacklistEntryResponse create(Long userId, CreateBlacklistEntryRequest request);

  /**
   * Returns a page of blacklist entries owned by the given user, optionally filtered by type.
   *
   * @param userId   the owning user's ID
   * @param type     the entry type to filter by, or {@code null} for all types
   * @param pageable pagination and sorting configuration
   * @return page of blacklist entries, never null
   */
  Page<BlacklistEntryResponse> findByUser(Long userId, BlacklistEntryType type, Pageable pageable);

  /**
   * Removes an entry from the given user's blacklist.
   *
   * @param userId the owning user's ID
   * @param id     the blacklist entry ID
   * @throws com.flatio.common.exception.BlacklistEntryNotFoundException if not found or not
   *     owned by the user
   */
  void delete(Long userId, Long id);
}
