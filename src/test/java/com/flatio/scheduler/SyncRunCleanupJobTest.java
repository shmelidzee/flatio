package com.flatio.scheduler;

import com.flatio.service.SyncRunService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SyncRunCleanupJobTest {

  @Mock
  private SyncRunService syncRunService;

  @InjectMocks
  private SyncRunCleanupJob cleanupJob;

  @BeforeEach
  void setUp() {
    ReflectionTestUtils.setField(cleanupJob, "keepPerSource", 100);
  }

  @Test
  void should_delegate_cleanup_with_configured_retention_count() {
    // When
    cleanupJob.runCleanup();

    // Then
    verify(syncRunService).cleanupOldRuns(100);
  }

  @Test
  void should_not_propagate_exception_when_cleanup_fails() {
    // Given
    doThrow(new RuntimeException("DB unavailable")).when(syncRunService).cleanupOldRuns(100);

    // When / Then
    assertThatNoException().isThrownBy(() -> cleanupJob.runCleanup());
  }
}
