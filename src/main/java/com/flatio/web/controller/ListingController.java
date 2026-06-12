package com.flatio.web.controller;

import com.flatio.service.ListingService;
import com.flatio.web.dto.ListingResponse;
import com.flatio.web.dto.ListingSearchCriteria;
import com.flatio.web.dto.ListingSummaryResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for listing search and retrieval operations.
 */
@RestController
@RequestMapping("/api/v1/listings")
@Tag(name = "Listings", description = "Search and retrieve real estate listings")
@RequiredArgsConstructor
public class ListingController {

  private final ListingService listingService;

  /**
   * Returns a paginated list of listings matching the given filter criteria.
   *
   * <p>All filter parameters are optional. Defaults to ACTIVE listings when {@code status} is omitted.
   *
   * @param criteria filter parameters bound from query string
   * @param pageable pagination and sorting (default: 20 per page, sorted by createdAt DESC)
   * @return page of matching listing summaries
   */
  @Operation(
      summary = "Search listings",
      description = "Returns a paginated list of listings matching the filter criteria. Defaults to ACTIVE listings when status is not specified."
  )
  @ApiResponse(responseCode = "200", description = "Listings page returned")
  @ApiResponse(responseCode = "400", description = "Invalid filter parameters")
  @ApiResponse(responseCode = "401", description = "Unauthorized")
  @GetMapping
  public Page<ListingSummaryResponse> search(
      @ModelAttribute ListingSearchCriteria criteria,
      @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable
  ) {
    return listingService.search(criteria, pageable);
  }

  /**
   * Returns the full details of a single listing including its price change history.
   *
   * @param id the internal listing identifier
   * @return full listing response with price history
   */
  @Operation(
      summary = "Get listing by ID",
      description = "Returns full details of a single listing including its price change history."
  )
  @ApiResponse(responseCode = "200", description = "Listing found")
  @ApiResponse(responseCode = "404", description = "Listing not found")
  @ApiResponse(responseCode = "401", description = "Unauthorized")
  @GetMapping("/{id}")
  public ListingResponse findById(@PathVariable Long id) {
    return listingService.findById(id);
  }
}
