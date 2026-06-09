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
   * @param criteria filter parameters; all fields are optional
   * @param pageable pagination and sorting configuration
   * @return page of matching listing summaries, never null
   */
  Page<ListingSummaryResponse> search(ListingSearchCriteria criteria, Pageable pageable);

  /**
   * Returns the full details of a listing by its internal ID, including price history.
   *
   * @param id the internal listing identifier
   * @return full listing response with price history, never null
   * @throws com.flatio.common.exception.ListingNotFoundException if no listing with the given ID exists
   */
  ListingResponse findById(Long id);
}
