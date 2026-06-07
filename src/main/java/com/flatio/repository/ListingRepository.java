package com.flatio.repository;

import com.flatio.domain.listing.Listing;
import com.flatio.domain.listing.ListingStatus;
import com.flatio.domain.source.Source;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ListingRepository extends JpaRepository<Listing, Long> {

  /**
   * Finds a listing by its external identifier and source.
   *
   * <p>Used for deduplication during parsing: each (externalId, sourceId) pair is unique.
   *
   * @param externalId the identifier assigned by the source platform
   * @param sourceId   the internal ID of the source
   * @return the listing if found, or empty
   */
  @Query("SELECT l FROM Listing l WHERE l.externalId = :externalId AND l.source.id = :sourceId")
  Optional<Listing> findByExternalIdAndSourceId(
      @Param("externalId") String externalId,
      @Param("sourceId") Long sourceId
  );

  /**
   * Finds listings with the given deduplication hash from any source except the specified one.
   *
   * <p>Used for cross-source duplicate detection: locates listings from other sources
   * that share the same computed hash, indicating they may be the same physical property.
   *
   * @param dedupHash the SHA-256 hash to search for, must not be null
   * @param source    the source to exclude from results
   * @return list of matching listings from other sources, never null, may be empty
   */
  List<Listing> findByDedupHashAndSourceNot(String dedupHash, Source source);

  /**
   * Finds all listings for a given country code and status.
   *
   * @param countryCode ISO country code (e.g., "BY")
   * @param status      listing status filter
   * @return list of matching listings, never null
   */
  @Query("SELECT l FROM Listing l JOIN FETCH l.source JOIN FETCH l.currency JOIN FETCH l.country " +
      "WHERE l.country.code = :countryCode AND l.status = :status")
  List<Listing> findByCountryCodeAndStatus(
      @Param("countryCode") String countryCode,
      @Param("status") ListingStatus status
  );

  /**
   * Finds a page of listings for a given country code and status.
   *
   * <p>Used for paginated listing feeds. Associations are loaded lazily;
   * use {@link #findByCountryCodeAndStatus(String, ListingStatus)} when eager loading is required.
   *
   * @param countryCode ISO country code (e.g., "BY")
   * @param status      listing status filter
   * @param pageable    pagination and sorting configuration
   * @return page of matching listings, never null
   */
  @Query(
      value = "SELECT l FROM Listing l WHERE l.country.code = :countryCode AND l.status = :status",
      countQuery = "SELECT COUNT(l) FROM Listing l WHERE l.country.code = :countryCode AND l.status = :status"
  )
  Page<Listing> findPageByCountryCodeAndStatus(
      @Param("countryCode") String countryCode,
      @Param("status") ListingStatus status,
      Pageable pageable
  );
}
