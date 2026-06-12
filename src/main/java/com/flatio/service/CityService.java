package com.flatio.service;

import com.flatio.domain.city.City;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Service for city reference data operations.
 */
public interface CityService {

  /**
   * Searches cities whose Russian name contains the given query (case-insensitive).
   *
   * <p>Returns an empty list when no cities match. Results are sorted alphabetically.
   *
   * @param query partial city name, never null
   * @return matching cities sorted by name_ru, never null
   */
  List<City> searchByName(String query);

  /**
   * Finds the city nearest to the given geographic coordinates.
   *
   * @param latitude  latitude in decimal degrees (WGS-84), never null
   * @param longitude longitude in decimal degrees (WGS-84), never null
   * @return optional city closest to the coordinates
   */
  Optional<City> findNearestCity(BigDecimal latitude, BigDecimal longitude);

  /**
   * Checks whether a city with the given ID exists in the reference table.
   *
   * @param id city identifier
   * @return true if the city exists, false otherwise
   */
  boolean existsById(Long id);
}
