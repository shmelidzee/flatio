package com.flatio.web.controller;

import com.flatio.domain.blacklist.BlacklistEntryType;
import com.flatio.service.BlacklistService;
import com.flatio.web.dto.BlacklistEntryResponse;
import com.flatio.web.dto.CreateBlacklistEntryRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for managing the authenticated user's blacklist: excluded listings, sources,
 * and stop-words.
 */
@RestController
@RequestMapping("/api/v1/blacklist")
@Tag(name = "Blacklist", description = "Manage the authenticated user's excluded listings, sources, and stop-words")
@RequiredArgsConstructor
public class BlacklistController {

  private final BlacklistService blacklistService;

  /**
   * Adds an entry to the authenticated user's blacklist.
   *
   * @param request        the entry to blacklist
   * @param authentication the authenticated caller
   * @return the created (or already existing) blacklist entry
   */
  @Operation(
      summary = "Add a blacklist entry",
      description = "Excludes a listing, a source, or a stop-word from the authenticated user's search results "
          + "and notifications. Fails with 422 if type=KEYWORD and the user's tariff stop-word limit is reached. "
          + "Re-adding an already-blacklisted entry is a no-op."
  )
  @ApiResponse(responseCode = "200", description = "Entry added to the blacklist")
  @ApiResponse(responseCode = "400", description = "Invalid request body or value format for the given type")
  @ApiResponse(responseCode = "401", description = "Unauthorized")
  @ApiResponse(responseCode = "404", description = "Referenced listing or source not found")
  @ApiResponse(responseCode = "422", description = "Stop-word limit exceeded")
  @PostMapping
  public BlacklistEntryResponse create(
      @Valid @RequestBody CreateBlacklistEntryRequest request,
      Authentication authentication
  ) {
    return blacklistService.create(currentUserId(authentication), request);
  }

  /**
   * Returns a paginated list of the authenticated user's blacklist entries.
   *
   * @param type           optional entry type filter
   * @param pageable       pagination and sorting (default: 20 per page, sorted by createdAt DESC)
   * @param authentication the authenticated caller
   * @return page of the caller's blacklist entries
   */
  @Operation(
      summary = "List my blacklist entries",
      description = "Returns a paginated list of the authenticated user's blacklist entries, optionally "
          + "filtered by type (LISTING, SOURCE, or KEYWORD)."
  )
  @ApiResponse(responseCode = "200", description = "Blacklist page returned")
  @ApiResponse(responseCode = "401", description = "Unauthorized")
  @GetMapping
  public Page<BlacklistEntryResponse> findMine(
      @Parameter(description = "Filter by entry type") @RequestParam(required = false) BlacklistEntryType type,
      @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable,
      Authentication authentication
  ) {
    return blacklistService.findByUser(currentUserId(authentication), type, pageable);
  }

  /**
   * Removes an entry from the authenticated user's blacklist.
   *
   * @param id             the ID of the blacklist entry to remove
   * @param authentication the authenticated caller
   */
  @Operation(summary = "Remove a blacklist entry", description = "Permanently removes a blacklist entry.")
  @ApiResponse(responseCode = "200", description = "Entry removed from the blacklist")
  @ApiResponse(responseCode = "404", description = "Blacklist entry not found")
  @ApiResponse(responseCode = "401", description = "Unauthorized")
  @DeleteMapping("/{id}")
  public void delete(@PathVariable Long id, Authentication authentication) {
    blacklistService.delete(currentUserId(authentication), id);
  }

  private Long currentUserId(Authentication authentication) {
    return Long.valueOf(authentication.getName());
  }
}
