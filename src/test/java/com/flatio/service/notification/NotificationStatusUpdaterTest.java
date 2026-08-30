package com.flatio.service.notification;

import com.flatio.domain.notification.Notification;
import com.flatio.domain.notification.NotificationStatus;
import com.flatio.domain.subscription.TriggerType;
import com.flatio.repository.NotificationRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class NotificationStatusUpdaterTest {

  @Mock
  private NotificationRepository notificationRepository;

  private NotificationStatusUpdater updater;
  private SimpleMeterRegistry meterRegistry;

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();
    updater = new NotificationStatusUpdater(notificationRepository, meterRegistry);
  }

  @Test
  void should_set_status_sent_and_timestamp_when_marking_sent() {
    // Given
    var notification = buildNotification();

    // When
    updater.markSent(notification);

    // Then
    assertThat(notification.getStatus()).isEqualTo(NotificationStatus.SENT);
    assertThat(notification.getSentAt()).isNotNull();
    verify(notificationRepository).save(notification);
    assertThat(meterRegistry.counter("flatio.notifications.sent", "triggerType", "NEW_LISTING").count()).isEqualTo(1.0);
  }

  @Test
  void should_set_status_failed_when_marking_failed() {
    // Given
    var notification = buildNotification();

    // When
    updater.markFailed(notification);

    // Then
    assertThat(notification.getStatus()).isEqualTo(NotificationStatus.FAILED);
    assertThat(notification.getSentAt()).isNull();
    verify(notificationRepository).save(notification);
    assertThat(meterRegistry.counter("flatio.notifications.failed", "triggerType", "NEW_LISTING").count()).isEqualTo(1.0);
  }

  private Notification buildNotification() {
    var notification = new Notification();
    notification.setId(1L);
    notification.setTriggerType(TriggerType.NEW_LISTING);
    notification.setStatus(NotificationStatus.PENDING);
    return notification;
  }
}
