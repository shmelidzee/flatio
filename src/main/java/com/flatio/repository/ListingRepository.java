package com.flatio.repository;

import com.flatio.domain.listing.Listing;
import com.flatio.domain.listing.ListingStatus;
import com.flatio.domain.source.Source;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ListingRepository extends JpaRepository<Listing, Long>, JpaSpecificationExecutor<Listing> {

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
   * Finds the first active listing with the same deduplication hash from the same source
   * but with a different external ID.
   *
   * <p>Used for within-source repost detection: if another active listing from the same source
   * shares the computed hash but has a different external ID, the incoming listing is a repost.
   * Only ACTIVE listings are considered originals — INACTIVE or REPOSTED listings are skipped.
   *
   * @param dedupHash  the deduplication hash to match
   * @param source     the source to search within
   * @param externalId the external ID to exclude
   * @param status     the status to filter by; pass {@link ListingStatus#ACTIVE} for repost detection
   * @return the original listing if a match is found, or empty
   */
  Optional<Listing> findFirstByDedupHashAndSourceAndExternalIdNotAndStatus(
      String dedupHash, Source source, String externalId, ListingStatus status);

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

  /**
   * Counts all listings for the given source.
   *
   * @param source the data source
   * @return total listing count, never negative
   */
  long countBySource(Source source);

  /**
   * Finds listings that need reverse geocoding: coordinates are known but city is not yet set.
   *
   * <p>Used by the background geocoding job to enrich listings in batches.
   *
   * @param pageable pagination configuration controlling batch size
   * @return page of listings needing geocoding, never null
   */
  @Query("SELECT l FROM Listing l WHERE l.city IS NULL AND l.latitude IS NOT NULL")
  List<Listing> findNeedingGeocoding(Pageable pageable);

  /**
   * Sets status to INACTIVE for all ACTIVE listings of the given source whose external ID
   * is not in the provided collection.
   *
   * <p>Used after a full sync to retire listings that disappeared from the source's API.
   * The caller must guarantee {@code activeExternalIds} is non-empty before invoking this method
   * to avoid accidentally deactivating every listing when a fetch returns no results.
   *
   * @param source            the data source whose listings to check
   * @param activeExternalIds external IDs that are still present in the source; must not be empty
   * @return number of listings updated
   */
  @Modifying
  @Query("UPDATE Listing l SET l.status = com.flatio.domain.listing.ListingStatus.INACTIVE " +
      "WHERE l.source = :source AND l.externalId NOT IN :activeExternalIds " +
      "AND l.status = com.flatio.domain.listing.ListingStatus.ACTIVE")
  int deactivateActiveBySourceExcluding(
      @Param("source") Source source,
      @Param("activeExternalIds") Collection<String> activeExternalIds
  );

  /**
   * Increments {@code missedSyncsCount} for all ACTIVE listings of the given source
   * whose external ID is absent from the provided collection.
   *
   * <p>Called after each full sync to track how many consecutive syncs a listing has been absent.
   * Only ACTIVE listings are counted — INACTIVE ones are already retired.
   * The caller must guarantee {@code activeExternalIds} is non-empty.
   *
   * @param source            the data source whose listings to check
   * @param activeExternalIds external IDs present in the current sync batch; must not be empty
   * @return number of listings whose counter was incremented
   */
  @Modifying
  @Query("UPDATE Listing l SET l.missedSyncsCount = l.missedSyncsCount + 1 " +
      "WHERE l.source = :source AND l.externalId NOT IN :activeExternalIds " +
      "AND l.status = com.flatio.domain.listing.ListingStatus.ACTIVE")
  int incrementMissedSyncsForAbsent(
      @Param("source") Source source,
      @Param("activeExternalIds") Collection<String> activeExternalIds
  );

  /**
   * Marks as INACTIVE all ACTIVE listings of the given source whose {@code missedSyncsCount}
   * has reached or exceeded the given threshold.
   *
   * @param source    the data source whose listings to check
   * @param threshold minimum missed sync count required to trigger deactivation
   * @return number of listings deactivated
   */
  @Modifying
  @Query("UPDATE Listing l SET l.status = com.flatio.domain.listing.ListingStatus.INACTIVE " +
      "WHERE l.source = :source " +
      "AND l.status = com.flatio.domain.listing.ListingStatus.ACTIVE " +
      "AND l.missedSyncsCount >= :threshold")
  int deactivateByMissedSyncsThreshold(
      @Param("source") Source source,
      @Param("threshold") int threshold
  );

  /**
   * Full-text search across listing title, description and address using PostgreSQL tsvector.
   *
   * <p>Uses {@code websearch_to_tsquery} which supports plain text, quoted phrases and
   * minus-word exclusion without additional parsing on the caller side.
   * Structural filters are applied alongside the FTS predicate; null parameters are ignored.
   *
   * @param query       full-text search string, must not be null or blank
   * @param ftsLanguage PostgreSQL text-search configuration name (e.g. "russian", "english")
   * @param status      listing status filter, as enum name string (e.g. "ACTIVE")
   * @param dealType    deal type filter (e.g. "RENT"), or null to skip
   * @param priceMin    minimum price inclusive, or null to skip
   * @param priceMax    maximum price inclusive, or null to skip
   * @param rooms       number of rooms filter, or null to skip
   * @param cityPattern SQL LIKE pattern for city name (e.g. "%минск%"), or null to skip
   * @param sourceCode  source platform code (e.g. "ONLINER"), or null to skip
   * @param propertyType property type filter (e.g. "APARTMENT"), or null to skip
   * @param pageable    pagination configuration
   * @return page of matching listings, never null
   */
  @Query(
      value = """
          SELECT l.id, l.external_id, l.source_id, l.title, l.description,
                 l.deal_type, l.price_unit, l.property_type, l.price, l.currency_id, l.price_usd,
                 l.rooms, l.floor_number, l.floors_total, l.area_total_m2, l.area_living_m2,
                 l.area_kitchen_m2, l.address, l.latitude, l.longitude, l.country_id,
                 l.city, l.district, l.status, l.source_url, l.dedup_hash,
                 l.is_owner, l.reposted_from, l.last_reposted_at,
                 l.missed_syncs_count, l.published_at, l.created_at, l.updated_at
          FROM listings l
          WHERE l.search_vector @@ websearch_to_tsquery(CAST(:ftsLanguage AS regconfig), :query)
            AND l.status = :status
            AND (:dealType IS NULL OR l.deal_type = :dealType)
            AND (:priceMin IS NULL OR l.price >= CAST(:priceMin AS numeric))
            AND (:priceMax IS NULL OR l.price <= CAST(:priceMax AS numeric))
            AND (:rooms IS NULL OR l.rooms = :rooms)
            AND (:cityPattern IS NULL OR LOWER(l.city) LIKE :cityPattern)
            AND (:sourceCode IS NULL OR l.source_id = (SELECT id FROM source WHERE code = :sourceCode))
            AND (:propertyType IS NULL OR l.property_type = :propertyType)
            AND (:ownerOnly IS NULL OR l.is_owner IS TRUE OR l.is_owner IS NULL)
          """,
      countQuery = """
          SELECT count(*) FROM listings l
          WHERE l.search_vector @@ websearch_to_tsquery(CAST(:ftsLanguage AS regconfig), :query)
            AND l.status = :status
            AND (:dealType IS NULL OR l.deal_type = :dealType)
            AND (:priceMin IS NULL OR l.price >= CAST(:priceMin AS numeric))
            AND (:priceMax IS NULL OR l.price <= CAST(:priceMax AS numeric))
            AND (:rooms IS NULL OR l.rooms = :rooms)
            AND (:cityPattern IS NULL OR LOWER(l.city) LIKE :cityPattern)
            AND (:sourceCode IS NULL OR l.source_id = (SELECT id FROM source WHERE code = :sourceCode))
            AND (:propertyType IS NULL OR l.property_type = :propertyType)
            AND (:ownerOnly IS NULL OR l.is_owner IS TRUE OR l.is_owner IS NULL)
          """,
      nativeQuery = true
  )
  Page<Listing> fullTextSearch(
      @Param("query") String query,
      @Param("ftsLanguage") String ftsLanguage,
      @Param("status") String status,
      @Param("dealType") String dealType,
      @Param("priceMin") BigDecimal priceMin,
      @Param("priceMax") BigDecimal priceMax,
      @Param("rooms") Integer rooms,
      @Param("cityPattern") String cityPattern,
      @Param("sourceCode") String sourceCode,
      @Param("propertyType") String propertyType,
      @Param("ownerOnly") Boolean ownerOnly,
      Pageable pageable
  );
}
