package com.flatio.scheduler;

import com.flatio.domain.alert.AlertType;
import com.flatio.domain.source.Source;
import com.flatio.repository.SourceRepository;
import com.flatio.service.SourceAlertService;
import com.flatio.service.SyncRunService;
import com.flatio.service.notification.SourceAlertNotifier;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SourceAlertCheckJobTest {

  @Mock
  private SourceRepository sourceRepository;

  @Mock
  private SyncRunService syncRunService;

  @Mock
  private SourceAlertService sourceAlertService;

  @Mock
  private SourceAlertNotifier notifier;

  @InjectMocks
  private SourceAlertCheckJob job;

  @BeforeEach
  void setUp() {
    ReflectionTestUtils.setField(job, "noSuccessHours", 3L);
    ReflectionTestUtils.setField(job, "errorRateWindowRuns", 5);
    ReflectionTestUtils.setField(job, "errorRateThreshold", 0.5);
    ReflectionTestUtils.setField(job, "cooldownHours", 6L);
  }

  // -------------------------------------------------------------------------
  // no-successful-sync rule
  // -------------------------------------------------------------------------

  @Test
  void should_skip_source_when_it_has_never_run() {
    // Given — brand new source, no runs recorded yet
    when(sourceRepository.findByActiveTrue()).thenReturn(List.of(buildSource("NEW_SOURCE")));
    when(syncRunService.hasAnyRun("NEW_SOURCE")).thenReturn(false);
    lenient().when(syncRunService.calculateRecentFailureRate(eq("NEW_SOURCE"), anyInt())).thenReturn(Optional.empty());

    // When
    job.checkSources();

    // Then — no alert decision made for a source that has never had a chance to run
    verify(sourceAlertService, never()).registerFailure(eq("NEW_SOURCE"), eq(AlertType.NO_SUCCESSFUL_SYNC), any());
    verify(sourceAlertService, never()).registerRecovery(eq("NEW_SOURCE"), eq(AlertType.NO_SUCCESSFUL_SYNC));
  }

  @Test
  void should_notify_when_source_has_run_but_never_succeeded() {
    // Given
    when(sourceRepository.findByActiveTrue()).thenReturn(List.of(buildSource("REALT")));
    when(syncRunService.hasAnyRun("REALT")).thenReturn(true);
    when(syncRunService.findLastSuccessfulRunAt("REALT")).thenReturn(Optional.empty());
    when(sourceAlertService.registerFailure(eq("REALT"), eq(AlertType.NO_SUCCESSFUL_SYNC), any())).thenReturn(true);
    lenient().when(syncRunService.calculateRecentFailureRate(eq("REALT"), anyInt())).thenReturn(Optional.empty());

    // When
    job.checkSources();

    // Then
    verify(notifier).sendFailureAlert(eq("REALT"), eq(AlertType.NO_SUCCESSFUL_SYNC), anyString());
  }

  @Test
  void should_notify_when_last_success_is_older_than_threshold() {
    // Given — last success 5 hours ago, threshold is 3 hours
    when(sourceRepository.findByActiveTrue()).thenReturn(List.of(buildSource("REALT")));
    when(syncRunService.hasAnyRun("REALT")).thenReturn(true);
    when(syncRunService.findLastSuccessfulRunAt("REALT"))
        .thenReturn(Optional.of(Instant.now().minus(Duration.ofHours(5))));
    when(sourceAlertService.registerFailure(eq("REALT"), eq(AlertType.NO_SUCCESSFUL_SYNC), any())).thenReturn(true);
    lenient().when(syncRunService.calculateRecentFailureRate(eq("REALT"), anyInt())).thenReturn(Optional.empty());

    // When
    job.checkSources();

    // Then
    verify(notifier).sendFailureAlert(eq("REALT"), eq(AlertType.NO_SUCCESSFUL_SYNC), anyString());
  }

  @Test
  void should_resolve_when_last_success_is_within_threshold() {
    // Given — last success 1 hour ago, threshold is 3 hours
    when(sourceRepository.findByActiveTrue()).thenReturn(List.of(buildSource("REALT")));
    when(syncRunService.hasAnyRun("REALT")).thenReturn(true);
    when(syncRunService.findLastSuccessfulRunAt("REALT"))
        .thenReturn(Optional.of(Instant.now().minus(Duration.ofHours(1))));
    when(sourceAlertService.registerRecovery("REALT", AlertType.NO_SUCCESSFUL_SYNC)).thenReturn(true);
    lenient().when(syncRunService.calculateRecentFailureRate(eq("REALT"), anyInt())).thenReturn(Optional.empty());

    // When
    job.checkSources();

    // Then
    verify(notifier).sendRecoveryNotice("REALT", AlertType.NO_SUCCESSFUL_SYNC);
    verify(notifier, never()).sendFailureAlert(eq("REALT"), eq(AlertType.NO_SUCCESSFUL_SYNC), anyString());
  }

  // -------------------------------------------------------------------------
  // error-rate rule
  // -------------------------------------------------------------------------

  @Test
  void should_notify_when_failure_rate_exceeds_threshold() {
    // Given — 80% failure rate, threshold is 50%
    when(sourceRepository.findByActiveTrue()).thenReturn(List.of(buildSource("REALT")));
    lenient().when(syncRunService.hasAnyRun("REALT")).thenReturn(false);
    when(syncRunService.calculateRecentFailureRate("REALT", 5)).thenReturn(Optional.of(0.8));
    when(sourceAlertService.registerFailure(eq("REALT"), eq(AlertType.HIGH_ERROR_RATE), any())).thenReturn(true);

    // When
    job.checkSources();

    // Then
    verify(notifier).sendFailureAlert(eq("REALT"), eq(AlertType.HIGH_ERROR_RATE), anyString());
  }

  @Test
  void should_resolve_when_failure_rate_at_or_below_threshold() {
    // Given — exactly at the threshold, not above it
    when(sourceRepository.findByActiveTrue()).thenReturn(List.of(buildSource("REALT")));
    lenient().when(syncRunService.hasAnyRun("REALT")).thenReturn(false);
    when(syncRunService.calculateRecentFailureRate("REALT", 5)).thenReturn(Optional.of(0.5));
    when(sourceAlertService.registerRecovery("REALT", AlertType.HIGH_ERROR_RATE)).thenReturn(true);

    // When
    job.checkSources();

    // Then
    verify(notifier).sendRecoveryNotice("REALT", AlertType.HIGH_ERROR_RATE);
  }

  @Test
  void should_treat_no_data_as_healthy_for_error_rate_rule() {
    // Given — source with no runs at all: not a "high error rate", nothing to resolve either
    when(sourceRepository.findByActiveTrue()).thenReturn(List.of(buildSource("NEW_SOURCE")));
    lenient().when(syncRunService.hasAnyRun("NEW_SOURCE")).thenReturn(false);
    when(syncRunService.calculateRecentFailureRate("NEW_SOURCE", 5)).thenReturn(Optional.empty());
    when(sourceAlertService.registerRecovery("NEW_SOURCE", AlertType.HIGH_ERROR_RATE)).thenReturn(false);

    // When
    job.checkSources();

    // Then
    verify(notifier, never()).sendFailureAlert(eq("NEW_SOURCE"), eq(AlertType.HIGH_ERROR_RATE), anyString());
    verify(notifier, never()).sendRecoveryNotice("NEW_SOURCE", AlertType.HIGH_ERROR_RATE);
  }

  // -------------------------------------------------------------------------
  // isolation across sources
  // -------------------------------------------------------------------------

  @Test
  void should_continue_checking_other_sources_when_one_source_check_throws() {
    // Given — checking the first source blows up, the second must still be checked
    when(sourceRepository.findByActiveTrue()).thenReturn(List.of(buildSource("BROKEN"), buildSource("REALT")));
    when(syncRunService.hasAnyRun("BROKEN")).thenThrow(new RuntimeException("DB hiccup"));
    when(syncRunService.hasAnyRun("REALT")).thenReturn(true);
    when(syncRunService.findLastSuccessfulRunAt("REALT"))
        .thenReturn(Optional.of(Instant.now().minus(Duration.ofHours(1))));
    when(sourceAlertService.registerRecovery("REALT", AlertType.NO_SUCCESSFUL_SYNC)).thenReturn(false);
    when(syncRunService.calculateRecentFailureRate("REALT", 5)).thenReturn(Optional.empty());

    // When / Then — no exception escapes the scheduled method
    assertThatNoException().isThrownBy(() -> job.checkSources());
    verify(syncRunService, times(1)).findLastSuccessfulRunAt("REALT");
  }

  private Source buildSource(String code) {
    var source = new Source();
    source.setCode(code);
    return source;
  }
}
