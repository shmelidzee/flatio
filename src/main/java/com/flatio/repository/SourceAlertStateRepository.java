package com.flatio.repository;

import com.flatio.domain.alert.AlertType;
import com.flatio.domain.alert.SourceAlertState;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SourceAlertStateRepository extends JpaRepository<SourceAlertState, Long> {

  /**
   * Finds the tracked alert state for the given source and alert type.
   *
   * @param sourceId  the connector source identifier
   * @param alertType the kind of alert
   * @return the tracked state if one exists (active or previously resolved), or empty
   */
  Optional<SourceAlertState> findBySourceIdAndAlertType(String sourceId, AlertType alertType);
}
