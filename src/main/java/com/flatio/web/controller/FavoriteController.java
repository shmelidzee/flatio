package com.flatio.web.controller;

import com.flatio.service.FavoriteService;
import com.flatio.web.dto.CreateFavoriteRequest;
import com.flatio.web.dto.FavoriteResponse;
import io.swagger.v3.oas.annotations.Operation;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for managing the authenticated user's favorited listings.
 */
@RestController
@RequestMapping("/api/v1/favorites")
@Tag(name = "Favorites", description = "Manage the authenticated user's favorited listings")
@RequiredArgsConstructor
public class FavoriteController {

  private final FavoriteService favoriteService;

  /**
   * Adds a listing to the authenticated user's favorites.
   *
   * @param request        the listing to favorite
   * @param authentication the authenticated caller
   * @return the created (or already existing) favorite entry
   */
  @Operation(
      summary = "Add a listing to favorites",
      description = "Adds a listing to the authenticated user's favorites. Fails with 422 if the "
          + "user's tariff favorites limit is reached. Re-adding an already-favorited listing is a no-op."
  )
  @ApiResponse(responseCode = "200", description = "Listing added to favorites")
  @ApiResponse(responseCode = "400", description = "Invalid request body")
  @ApiResponse(responseCode = "401", description = "Unauthorized")
  @ApiResponse(responseCode = "404", description = "Listing not found")
  @ApiResponse(responseCode = "422", description = "Favorites limit exceeded")
  @PostMapping
  public FavoriteResponse create(
      @Valid @RequestBody CreateFavoriteRequest request,
      Authentication authentication
  ) {
    return favoriteService.create(currentUserId(authentication), request);
  }

  /**
   * Returns a paginated list of the authenticated user's favorited listings.
   *
   * <p>Sortable via the standard {@code sort} query parameter, e.g. {@code sort=createdAt,desc}
   * (date added), {@code sort=listing.price,asc} (current price), or {@code sort=priceChange,desc}
   * (largest price change first).
   *
   * @param pageable       pagination and sorting (default: 20 per page, sorted by createdAt DESC)
   * @param authentication the authenticated caller
   * @return page of the caller's favorited listings
   */
  @Operation(
      summary = "List my favorites",
      description = "Returns a paginated list of listings favorited by the authenticated user. Sort by "
          + "date added (sort=createdAt), current price (sort=listing.price), or price change (sort=priceChange)."
  )
  @ApiResponse(responseCode = "200", description = "Favorites page returned")
  @ApiResponse(responseCode = "401", description = "Unauthorized")
  @GetMapping
  public Page<FavoriteResponse> findMine(
      @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable,
      Authentication authentication
  ) {
    return favoriteService.findByUser(currentUserId(authentication), pageable);
  }

  /**
   * Removes a listing from the authenticated user's favorites.
   *
   * @param listingId      the ID of the listing to remove
   * @param authentication the authenticated caller
   */
  @Operation(summary = "Remove a listing from favorites", description = "Permanently removes a favorited listing.")
  @ApiResponse(responseCode = "200", description = "Listing removed from favorites")
  @ApiResponse(responseCode = "404", description = "Favorite not found")
  @ApiResponse(responseCode = "401", description = "Unauthorized")
  @DeleteMapping("/{listingId}")
  public void delete(@PathVariable Long listingId, Authentication authentication) {
    favoriteService.delete(currentUserId(authentication), listingId);
  }

  private Long currentUserId(Authentication authentication) {
    return Long.valueOf(authentication.getName());
  }
}
