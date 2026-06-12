package com.flatio.repository;

import com.flatio.domain.city.CityAlias;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository for {@link CityAlias} entities.
 */
public interface CityAliasRepository extends JpaRepository<CityAlias, Long> {

  /**
   * Finds a city alias by the alias string and the source identifier.
   *
   * <p>Used to resolve a raw city name returned by an external source
   * to the canonical {@link com.flatio.domain.city.City} record.
   *
   * @param alias    the alias string as it appears in the external source
   * @param sourceId the identifier of the external source (e.g. "realt", "onliner", "kufar")
   * @return the city alias if found, or empty
   */
  Optional<CityAlias> findByAliasAndSourceId(String alias, String sourceId);
}
