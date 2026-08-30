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
class DigestNotificationDeliveryJobTest {

  @Mock
  private BatchNotificationSender batchNotificationSender;

  @InjectMocks
  private DigestNotificationDeliveryJob digestNotificationDeliveryJob;

  @Test
  void should_delegate_to_sender_when_triggered() {
    // Given / When
    digestNotificationDeliveryJob.runDigestDelivery();

    // Then
    verify(batchNotificationSender).sendDigest();
  }

  @Test
  void should_not_propagate_exception_when_sender_fails() {
    // Given
    doThrow(new RuntimeException("db unavailable")).when(batchNotificationSender).sendDigest();

    // When / Then — a failed run must not abort the scheduler or the next trigger
    assertThatNoException().isThrownBy(() -> digestNotificationDeliveryJob.runDigestDelivery());
  }
}
