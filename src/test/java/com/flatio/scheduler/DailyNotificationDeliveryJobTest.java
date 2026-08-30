package com.flatio.scheduler;

import com.flatio.service.notification.BatchNotificationSender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class DailyNotificationDeliveryJobTest {

  @Mock
  private BatchNotificationSender batchNotificationSender;

  @InjectMocks
  private DailyNotificationDeliveryJob dailyNotificationDeliveryJob;

  @Test
  void should_delegate_to_sender_when_triggered() {
    // Given / When
    dailyNotificationDeliveryJob.runDailyDelivery();

    // Then
    verify(batchNotificationSender).sendDaily();
  }

  @Test
  void should_not_propagate_exception_when_sender_fails() {
    // Given
    doThrow(new RuntimeException("db unavailable")).when(batchNotificationSender).sendDaily();

    // When / Then — a failed run must not abort the scheduler or the next trigger
    assertThatNoException().isThrownBy(() -> dailyNotificationDeliveryJob.runDailyDelivery());
  }
}
