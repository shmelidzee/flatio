package com.flatio.service;

import com.flatio.web.dto.CreateFavoriteRequest;
import com.flatio.web.dto.FavoriteResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Service for managing the listings a user has marked as favorites.
 */
public interface FavoriteService {

  /**
   * Adds a listing to the given user's favorites.
   *
   * <p>Re-adding a listing that is already favorited is idempotent: the existing favorite is
   * returned unchanged and does not count against the tariff limit again.
   *
   * @param userId  the owning user's ID
   * @param request the listing to favorite
   * @return the created (or already existing) favorite
   * @throws com.flatio.common.exception.ListingNotFoundException if the listing does not exist
   * @throws com.flatio.common.exception.FavoriteLimitExceededException if the user has reached
   *     their tariff's favorites limit
   */
  FavoriteResponse create(Long userId, CreateFavoriteRequest request);

  /**
   * Returns a page of favorites owned by the given user.
   *
   * @param userId   the owning user's ID
   * @param pageable pagination and sorting configuration
   * @return page of favorites, never null
   */
  Page<FavoriteResponse> findByUser(Long userId, Pageable pageable);

  /**
   * Removes a listing from the given user's favorites.
   *
   * @param userId    the owning user's ID
   * @param listingId the favorited listing's ID
   * @throws com.flatio.common.exception.FavoriteNotFoundException if not found or not owned by the user
   */
  void delete(Long userId, Long listingId);
}
