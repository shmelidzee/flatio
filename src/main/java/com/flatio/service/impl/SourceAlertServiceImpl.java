package com.flatio.service.impl;

import com.flatio.domain.alert.AlertType;
import com.flatio.domain.alert.SourceAlertState;
import com.flatio.repository.SourceAlertStateRepository;
import com.flatio.service.SourceAlertService;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@Slf4j
@RequiredArgsConstructor
public class SourceAlertServiceImpl implements SourceAlertService {

  private final SourceAlertStateRepository sourceAlertStateRepository;

  @Override
  @Transactional
  public boolean registerFailure(String sourceId, AlertType alertType, Duration cooldown) {
    var existing = sourceAlertStateRepository.findBySourceIdAndAlertType(sourceId, alertType);
    Instant now = Instant.now();

    if (existing.isEmpty()) {
      saveNewlyTriggered(sourceId, alertType, now);
      return true;
    }

    SourceAlertState state = existing.get();
    if (!state.isActive()) {
      state.setActive(true);
      state.setFirstTriggeredAt(now);
      state.setLastNotifiedAt(now);
      sourceAlertStateRepository.save(state);
      return true;
    }

    if (Duration.between(state.getLastNotifiedAt(), now).compareTo(cooldown) >= 0) {
      state.setLastNotifiedAt(now);
      sourceAlertStateRepository.save(state);
      return true;
    }

    return false;
  }

  @Override
  @Transactional
  public boolean registerRecovery(String sourceId, AlertType alertType) {
    var existing = sourceAlertStateRepository.findBySourceIdAndAlertType(sourceId, alertType);
    if (existing.isEmpty() || !existing.get().isActive()) {
      return false;
    }

    SourceAlertState state = existing.get();
    state.setActive(false);
    sourceAlertStateRepository.save(state);
    log.info("Source alert resolved: source={}, type={}", sourceId, alertType);
    return true;
  }

  private void saveNewlyTriggered(String sourceId, AlertType alertType, Instant now) {
    SourceAlertState state = new SourceAlertState();
    state.setSourceId(sourceId);
    state.setAlertType(alertType);
    state.setActive(true);
    state.setFirstTriggeredAt(now);
    state.setLastNotifiedAt(now);
    sourceAlertStateRepository.save(state);
    log.warn("Source alert triggered: source={}, type={}", sourceId, alertType);
  }
}
