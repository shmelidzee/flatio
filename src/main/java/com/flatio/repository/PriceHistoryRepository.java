package com.flatio.repository;

import com.flatio.domain.listing.Listing;
import com.flatio.domain.listing.PriceHistory;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PriceHistoryRepository extends JpaRepository<PriceHistory, Long> {

  /**
   * Returns the full price history for a listing, ordered by recording time descending.
   *
   * <p>Fetches currency eagerly to avoid N+1 when rendering history.
   * Results are ordered newest-first to support typical "latest price" queries.
   *
   * @param listing the listing whose price history is requested
   * @return list of price history records, never null, may be empty
   */
  @Query("SELECT ph FROM PriceHistory ph JOIN FETCH ph.currency WHERE ph.listing = :listing ORDER BY ph.recordedAt DESC")
  List<PriceHistory> findByListingOrderByRecordedAtDesc(@Param("listing") Listing listing);
}
