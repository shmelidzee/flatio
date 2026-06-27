package com.flatio.scheduler;

import com.flatio.service.SyncRunService;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.actuate.health.Status;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DataFreshnessHealthIndicatorTest {

  @Mock
  private SyncRunService syncRunService;

  @InjectMocks
  private DataFreshnessHealthIndicator healthIndicator;

  @BeforeEach
  void setUp() {
    ReflectionTestUtils.setField(healthIndicator, "thresholdMinutes", 30L);
  }

  // -------------------------------------------------------------------------
  // UP cases
  // -------------------------------------------------------------------------

  @Test
  void should_return_up_when_last_sync_was_within_threshold() {
    // Given — last sync finished 10 minutes ago
    Instant recent = Instant.now().minusSeconds(600);
    when(syncRunService.findLastSuccessfulRunAt()).thenReturn(Optional.of(recent));

    // When
    var health = healthIndicator.health();

    // Then
    assertThat(health.getStatus()).isEqualTo(Status.UP);
  }

  @Test
  void should_include_last_sync_and_age_in_details_when_up() {
    // Given
    Instant recent = Instant.now().minusSeconds(300);
    when(syncRunService.findLastSuccessfulRunAt()).thenReturn(Optional.of(recent));

    // When
    var health = healthIndicator.health();

    // Then — details are populated
    assertThat(health.getDetails()).containsKey("lastSync");
    assertThat(health.getDetails()).containsKey("ageMinutes");
    assertThat((Long) health.getDetails().get("ageMinutes")).isLessThanOrEqualTo(30L);
  }

  // -------------------------------------------------------------------------
  // DOWN cases
  // -------------------------------------------------------------------------

  @Test
  void should_return_unknown_when_no_successful_sync_run_exists() {
    // Given — first boot, sync_runs table is empty
    when(syncRunService.findLastSuccessfulRunAt()).thenReturn(Optional.empty());

    // When
    var health = healthIndicator.health();

    // Then — UNKNOWN, not DOWN: initial sync pending is not a failure
    assertThat(health.getStatus()).isEqualTo(Status.UNKNOWN);
    assertThat(health.getDetails()).containsKey("reason");
  }

  @Test
  void should_return_down_when_last_sync_exceeds_threshold() {
    // Given — last sync was 40 minutes ago (threshold is 30)
    Instant stale = Instant.now().minusSeconds(40 * 60);
    when(syncRunService.findLastSuccessfulRunAt()).thenReturn(Optional.of(stale));

    // When
    var health = healthIndicator.health();

    // Then
    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
  }

  @Test
  void should_include_age_details_when_down_due_to_stale_sync() {
    // Given — last sync was 45 minutes ago
    Instant stale = Instant.now().minusSeconds(45 * 60);
    when(syncRunService.findLastSuccessfulRunAt()).thenReturn(Optional.of(stale));

    // When
    var health = healthIndicator.health();

    // Then — details describe the staleness
    assertThat(health.getDetails()).containsKey("lastSync");
    assertThat(health.getDetails()).containsKey("ageMinutes");
    assertThat((Long) health.getDetails().get("ageMinutes")).isGreaterThan(30L);
  }

  @Test
  void should_return_down_when_last_sync_is_exactly_at_threshold_plus_one_minute() {
    // Given — exactly 31 minutes ago exceeds the 30-minute threshold
    Instant borderline = Instant.now().minusSeconds(31 * 60);
    when(syncRunService.findLastSuccessfulRunAt()).thenReturn(Optional.of(borderline));

    // When
    var health = healthIndicator.health();

    // Then
    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
  }

  @Test
  void should_return_up_when_last_sync_is_exactly_at_threshold() {
    // Given — exactly 30 minutes ago equals the threshold (not exceeds)
    Instant atThreshold = Instant.now().minusSeconds(30 * 60);
    when(syncRunService.findLastSuccessfulRunAt()).thenReturn(Optional.of(atThreshold));

    // When
    var health = healthIndicator.health();

    // Then — ≤ 30 minutes is still UP
    assertThat(health.getStatus()).isEqualTo(Status.UP);
  }
}
