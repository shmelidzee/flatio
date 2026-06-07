package com.flatio.repository;

import com.flatio.domain.country.Country;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CountryRepository extends JpaRepository<Country, Long> {

  /**
   * Finds a country by its ISO code.
   *
   * @param code the ISO country code (e.g., "BY")
   * @return the country if found, or empty
   */
  Optional<Country> findByCode(String code);
}
