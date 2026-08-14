package com.flatio.service;

import com.flatio.domain.listing.ListingStatus;
import com.flatio.web.dto.AdminListingSearchCriteria;
import com.flatio.web.dto.ListingSummaryResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Admin service for searching and manually managing listings.
 */
public interface AdminListingService {

  /**
   * Searches listings using admin filter criteria, across any status unless {@code status} is set.
   *
   * @param criteria filter parameters; all fields are optional
   * @param pageable pagination and sorting configuration
   * @return page of matching listing summaries, never null
   */
  Page<ListingSummaryResponse> search(AdminListingSearchCriteria criteria, Pageable pageable);

  /**
   * Manually changes a listing's status.
   *
   * @param id      the listing ID
   * @param status  the new status
   * @param adminId identifier of the admin performing the action, for audit logging
   * @return the updated listing summary
   * @throws com.flatio.common.exception.ListingNotFoundException if no listing with the given ID exists
   */
  ListingSummaryResponse updateStatus(Long id, ListingStatus status, String adminId);

  /**
   * Removes a listing from its duplicate group by clearing its deduplication hash.
   *
   * <p>Other listings that previously matched this one by hash are left untouched — only this
   * listing stops being considered a duplicate of them.
   *
   * @param id      the listing ID
   * @param adminId identifier of the admin performing the action, for audit logging
   * @throws com.flatio.common.exception.ListingNotFoundException if no listing with the given ID exists
   */
  void unlinkDuplicateGroup(Long id, String adminId);
}
