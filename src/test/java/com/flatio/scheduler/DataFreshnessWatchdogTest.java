package com.flatio.scheduler;

import com.flatio.integration.onliner.scheduler.OnlinerDeltaSyncJob;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DataFreshnessWatchdogTest {

  @Mock
  private DataFreshnessHealthIndicator healthIndicator;

  @Mock
  private OnlinerDeltaSyncJob onlinerDeltaSyncJob;

  @InjectMocks
  private DataFreshnessWatchdog watchdog;

  @Test
  void should_trigger_delta_sync_when_health_is_down() {
    // Given — health indicator reports DOWN (stale data)
    when(healthIndicator.health()).thenReturn(Health.down().build());

    // When
    watchdog.checkAndHeal();

    // Then — unplanned delta sync is triggered
    verify(onlinerDeltaSyncJob).runDeltaSync();
  }

  @Test
  void should_not_trigger_delta_sync_when_health_is_up() {
    // Given — health indicator reports UP (data is fresh)
    when(healthIndicator.health()).thenReturn(Health.up().build());

    // When
    watchdog.checkAndHeal();

    // Then — no unnecessary sync triggered
    verify(onlinerDeltaSyncJob, never()).runDeltaSync();
  }

  @Test
  void should_call_health_indicator_on_every_check() {
    // Given
    when(healthIndicator.health()).thenReturn(Health.up().build());

    // When
    watchdog.checkAndHeal();

    // Then — health is always evaluated before deciding whether to sync
    verify(healthIndicator).health();
  }
}
