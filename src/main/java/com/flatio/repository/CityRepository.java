package com.flatio.repository;

import com.flatio.domain.city.City;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

  /**
   * Finds cities whose Russian name contains the given query (case-insensitive).
   *
   * <p>Results are sorted alphabetically by Russian name.
   * Returns an empty list if no cities match the query.
   *
   * @param query partial city name to search for, never null
   * @return list of matching cities sorted by name_ru, never null
   */
  @Query("SELECT c FROM City c WHERE LOWER(c.nameRu) LIKE LOWER(CONCAT('%', :query, '%')) ORDER BY c.nameRu")
  List<City> findByNameRuContainingIgnoreCase(@Param("query") String query);

  /**
   * Finds the city nearest to the given geographic coordinates.
   *
   * <p>Uses Euclidean distance approximation on stored decimal degrees.
   * Suitable for finding the closest city when a user shares Telegram location.
   * Returns empty if no cities exist in the database.
   *
   * @param latitude  latitude in decimal degrees (WGS-84), never null
   * @param longitude longitude in decimal degrees (WGS-84), never null
   * @return optional city closest to the given coordinates
   */
  @Query(value = """
      SELECT id, name_ru, name_be, name_en, country_id, latitude, longitude, created_at, updated_at
      FROM cities
      ORDER BY (latitude - :lat) * (latitude - :lat) + (longitude - :lon) * (longitude - :lon)
      LIMIT 1
      """, nativeQuery = true)
  Optional<City> findNearestCity(@Param("lat") BigDecimal latitude, @Param("lon") BigDecimal longitude);
}
