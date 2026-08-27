package com.flatio.service;

import com.flatio.web.dto.ListingResponse;
import com.flatio.web.dto.ListingSearchCriteria;
import com.flatio.web.dto.ListingSummaryResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Service for listing domain operations — search and retrieval.
 */
public interface ListingService {

  /**
   * Searches listings using the given filter criteria with pagination.
   *
   * <p>Returns ACTIVE listings when {@code criteria.status()} is null.
   * Source and currency associations are eagerly loaded to prevent N+1 queries.
   *
   * <p>When {@code userId} is not null, listings the user has blacklisted are excluded: an
   * individual listing (by ID), an entire source (by code), or any listing whose title,
   * description, or address matches one of the user's stop-words. Pass {@code null} for an
   * anonymous or otherwise user-less caller to skip blacklist exclusion entirely (issue #414).
   *
   * <p>Each result's {@code displayPrice}/{@code displayCurrency} (issue #415) is the stored
   * price converted into {@code targetCurrency} (or BYN when {@code targetCurrency} is null),
   * read-time only — the stored {@code price}/{@code currency} are never modified.
   *
   * @param criteria       filter parameters; all fields are optional
   * @param pageable       pagination and sorting configuration
   * @param userId         the authenticated caller's ID for blacklist exclusion, or null to skip it
   * @param targetCurrency currency to convert each result's display price into, or null for BYN
   * @return page of matching listing summaries, never null
   */
  Page<ListingSummaryResponse> search(ListingSearchCriteria criteria, Pageable pageable, Long userId,
      String targetCurrency);

  /**
   * Returns the full details of a listing by its internal ID, including price history.
   *
   * <p>{@code displayPrice}/{@code displayCurrency} (issue #415) is the stored price converted
   * into {@code targetCurrency} (or BYN when null), read-time only.
   *
   * @param id             the internal listing identifier
   * @param targetCurrency currency to convert the display price into, or null for BYN
   * @return full listing response with price history, never null
   * @throws com.flatio.common.exception.ListingNotFoundException if no listing with the given ID exists
   */
  ListingResponse findById(Long id, String targetCurrency);
}
