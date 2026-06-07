package com.flatio.repository;

import com.flatio.domain.listing.Listing;
import com.flatio.domain.listing.ListingStatus;
import java.util.List;
import java.util.Optional;
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
}
