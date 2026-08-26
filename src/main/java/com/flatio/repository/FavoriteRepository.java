package com.flatio.repository;

import com.flatio.domain.favorite.Favorite;
import com.flatio.domain.user.User;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FavoriteRepository extends JpaRepository<Favorite, Long> {

  /**
   * Returns a page of favorites owned by the given user.
   *
   * <p>Eagerly fetches {@code listing}, {@code listing.source} and {@code listing.currency} in a
   * single query, since {@link com.flatio.web.mapper.FavoriteMapper} reads all three for every
   * row of the page; without the fetch join, OSIV would issue them as separate lazy-load
   * SELECTs per favorite. A separate {@code countQuery} is required because {@code JOIN FETCH}
   * combined with {@link Pageable} cannot compute the total row count from the fetch-joined query.
   *
   * @param user     the owning user
   * @param pageable pagination and sorting configuration
   * @return page of favorites, never null
   */
  @Query(
      value = "SELECT f FROM Favorite f JOIN FETCH f.listing l JOIN FETCH l.source JOIN FETCH l.currency "
          + "WHERE f.user = :user",
      countQuery = "SELECT COUNT(f) FROM Favorite f WHERE f.user = :user"
  )
  Page<Favorite> findByUser(@Param("user") User user, Pageable pageable);

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
