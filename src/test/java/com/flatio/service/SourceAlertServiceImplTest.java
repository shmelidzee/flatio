package com.flatio.service;

import com.flatio.domain.alert.AlertType;
import com.flatio.domain.alert.SourceAlertState;
import com.flatio.repository.SourceAlertStateRepository;
import com.flatio.service.impl.SourceAlertServiceImpl;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SourceAlertServiceImplTest {

  @Mock
  private SourceAlertStateRepository sourceAlertStateRepository;

  @InjectMocks
  private SourceAlertServiceImpl alertService;

  // -------------------------------------------------------------------------
  // registerFailure
  // -------------------------------------------------------------------------

  @Test
  void should_notify_when_alert_triggered_for_the_first_time() {
    // Given — no tracked state yet for this source/type
    when(sourceAlertStateRepository.findBySourceIdAndAlertType("REALT", AlertType.NO_SUCCESSFUL_SYNC))
        .thenReturn(Optional.empty());

    // When
    boolean shouldNotify = alertService.registerFailure("REALT", AlertType.NO_SUCCESSFUL_SYNC, Duration.ofHours(6));

    // Then
    assertThat(shouldNotify).isTrue();
    var captor = ArgumentCaptor.forClass(SourceAlertState.class);
    verify(sourceAlertStateRepository).save(captor.capture());
    assertThat(captor.getValue().getSourceId()).isEqualTo("REALT");
    assertThat(captor.getValue().getAlertType()).isEqualTo(AlertType.NO_SUCCESSFUL_SYNC);
    assertThat(captor.getValue().isActive()).isTrue();
  }

  @Test
  void should_not_notify_again_when_already_active_and_cooldown_not_elapsed() {
    // Given — notified 1 hour ago, cooldown is 6 hours
    var state = buildState(true, Instant.now().minus(Duration.ofHours(1)));
    when(sourceAlertStateRepository.findBySourceIdAndAlertType("REALT", AlertType.NO_SUCCESSFUL_SYNC))
        .thenReturn(Optional.of(state));

    // When
    boolean shouldNotify = alertService.registerFailure("REALT", AlertType.NO_SUCCESSFUL_SYNC, Duration.ofHours(6));

    // Then
    assertThat(shouldNotify).isFalse();
    verify(sourceAlertStateRepository, never()).save(any());
  }

  @Test
  void should_notify_again_when_already_active_and_cooldown_elapsed() {
    // Given — last notified 7 hours ago, cooldown is 6 hours
    var state = buildState(true, Instant.now().minus(Duration.ofHours(7)));
    when(sourceAlertStateRepository.findBySourceIdAndAlertType("REALT", AlertType.NO_SUCCESSFUL_SYNC))
        .thenReturn(Optional.of(state));

    // When
    boolean shouldNotify = alertService.registerFailure("REALT", AlertType.NO_SUCCESSFUL_SYNC, Duration.ofHours(6));

    // Then
    assertThat(shouldNotify).isTrue();
    verify(sourceAlertStateRepository).save(state);
  }

  @Test
  void should_notify_when_a_previously_resolved_alert_triggers_again() {
    // Given — a resolved (inactive) row exists from a past incident
    var state = buildState(false, Instant.now().minus(Duration.ofDays(1)));
    when(sourceAlertStateRepository.findBySourceIdAndAlertType("REALT", AlertType.NO_SUCCESSFUL_SYNC))
        .thenReturn(Optional.of(state));

    // When
    boolean shouldNotify = alertService.registerFailure("REALT", AlertType.NO_SUCCESSFUL_SYNC, Duration.ofHours(6));

    // Then
    assertThat(shouldNotify).isTrue();
    assertThat(state.isActive()).isTrue();
  }

  // -------------------------------------------------------------------------
  // registerRecovery
  // -------------------------------------------------------------------------

  @Test
  void should_resolve_and_notify_when_alert_was_active() {
    // Given
    var state = buildState(true, Instant.now());
    when(sourceAlertStateRepository.findBySourceIdAndAlertType("REALT", AlertType.HIGH_ERROR_RATE))
        .thenReturn(Optional.of(state));

    // When
    boolean recovered = alertService.registerRecovery("REALT", AlertType.HIGH_ERROR_RATE);

    // Then
    assertThat(recovered).isTrue();
    assertThat(state.isActive()).isFalse();
    verify(sourceAlertStateRepository).save(state);
  }

  @Test
  void should_do_nothing_when_no_alert_was_active() {
    // Given — no tracked state at all
    when(sourceAlertStateRepository.findBySourceIdAndAlertType("REALT", AlertType.HIGH_ERROR_RATE))
        .thenReturn(Optional.empty());

    // When
    boolean recovered = alertService.registerRecovery("REALT", AlertType.HIGH_ERROR_RATE);

    // Then
    assertThat(recovered).isFalse();
    verify(sourceAlertStateRepository, never()).save(any());
  }

  @Test
  void should_do_nothing_when_alert_already_resolved() {
    // Given — already inactive
    var state = buildState(false, Instant.now());
    when(sourceAlertStateRepository.findBySourceIdAndAlertType("REALT", AlertType.HIGH_ERROR_RATE))
        .thenReturn(Optional.of(state));

    // When
    boolean recovered = alertService.registerRecovery("REALT", AlertType.HIGH_ERROR_RATE);

    // Then
    assertThat(recovered).isFalse();
    verify(sourceAlertStateRepository, never()).save(any());
  }

  private SourceAlertState buildState(boolean active, Instant lastNotifiedAt) {
    var state = new SourceAlertState();
    state.setSourceId("REALT");
    state.setAlertType(AlertType.NO_SUCCESSFUL_SYNC);
    state.setActive(active);
    state.setFirstTriggeredAt(lastNotifiedAt);
    state.setLastNotifiedAt(lastNotifiedAt);
    return state;
  }
}
