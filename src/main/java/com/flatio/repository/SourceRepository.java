package com.flatio.repository;

import com.flatio.domain.source.Source;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SourceRepository extends JpaRepository<Source, Long> {

  /**
   * Finds a source by its unique code.
   *
   * @param code the source code (e.g., "ONLINER")
   * @return the source if found, or empty
   */
  Optional<Source> findByCode(String code);

  /**
   * Finds all active sources for the given country code.
   *
   * @param countryCode the ISO country code (e.g., "BY")
   * @return list of active sources for the given country, never null
   */
  @Query("SELECT s FROM Source s JOIN FETCH s.country WHERE s.country.code = :countryCode AND s.active = true")
  List<Source> findActiveByCountryCode(@Param("countryCode") String countryCode);
}
