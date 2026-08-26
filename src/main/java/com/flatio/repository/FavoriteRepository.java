package com.flatio.repository;

import com.flatio.domain.favorite.Favorite;
import com.flatio.domain.user.User;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FavoriteRepository extends JpaRepository<Favorite, Long> {

  /**
   * Returns a page of favorites owned by the given user.
   *
   * @param user     the owning user
   * @param pageable pagination and sorting configuration
   * @return page of favorites, never null
   */
  Page<Favorite> findByUser(User user, Pageable pageable);

  /**
   * Finds a favorite by owner and favorited listing.
   *
   * <p>Used both to enforce that a user can only access their own favorites, and to look up an
   * existing favorite for a listing before creating a new one.
   *
   * @param user      the expected owner
   * @param listingId the favorited listing's ID
   * @return the favorite if found and owned by {@code user}, or empty
   */
  Optional<Favorite> findByUserAndListingId(User user, Long listingId);

  /**
   * Counts the favorites owned by the given user.
   *
   * @param user the owning user
   * @return number of favorites
   */
  long countByUser(User user);
}
