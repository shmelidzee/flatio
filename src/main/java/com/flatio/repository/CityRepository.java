package com.flatio.repository;

import com.flatio.domain.city.City;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository for {@link City} entities.
 */
public interface CityRepository extends JpaRepository<City, Long> {

  /**
   * Finds a city by its canonical Russian name.
   *
   * @param nameRu the canonical Russian city name (e.g. «Минск»)
   * @return the city if found, or empty
   */
  Optional<City> findByNameRu(String nameRu);
}
