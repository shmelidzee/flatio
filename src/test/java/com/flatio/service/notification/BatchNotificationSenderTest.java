package com.flatio.service.notification;

import com.flatio.config.NotificationDailyProperties;
import com.flatio.config.NotificationDigestProperties;
import com.flatio.domain.listing.Listing;
import com.flatio.domain.notification.Notification;
import com.flatio.domain.notification.NotificationStatus;
import com.flatio.domain.subscription.DeliveryMode;
import com.flatio.domain.subscription.Subscription;
import com.flatio.domain.subscription.TriggerType;
import com.flatio.domain.user.User;
import com.flatio.repository.NotificationRepository;
import com.flatio.telegram.formatter.ListingFormatter;
import com.flatio.web.dto.ListingSummaryResponse;
import com.flatio.web.mapper.ListingMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BatchNotificationSenderTest {

  @Mock
  private NotificationRepository notificationRepository;

  @Mock
  private TelegramChatResolver chatResolver;

  @Mock
  private NotificationStatusUpdater statusUpdater;

  @Mock
  private ListingMapper listingMapper;

  @Mock
  private ListingFormatter listingFormatter;

  @Mock
  private TelegramClient telegramClient;

  private BatchNotificationSender sender;

  @BeforeEach
  void setUp() {
    var digestProperties = new NotificationDigestProperties(200, 60);
    var dailyProperties = new NotificationDailyProperties(200);
    sender = new BatchNotificationSender(
        notificationRepository, chatResolver, statusUpdater, listingMapper, listingFormatter,
        telegramClient, digestProperties, dailyProperties, new SimpleMeterRegistry()
    );
  }

  // -------------------------------------------------------------------------
  // sendDigest — batch selection
  // -------------------------------------------------------------------------

  @Test
  void should_do_nothing_when_digest_batch_empty() {
    // Given
    when(notificationRepository.findPendingForDigest(any(), any(), any(), any(Instant.class), any(Pageable.class)))
        .thenReturn(List.of());

    // When
    sender.sendDigest();

    // Then
    verify(chatResolver, never()).resolveChatId(any());
  }

  @Test
  void should_request_digest_and_realtime_overflow_from_repository() {
    // Given
    when(notificationRepository.findPendingForDigest(any(), any(), any(), any(Instant.class), any(Pageable.class)))
        .thenReturn(List.of());

    // When
    sender.sendDigest();

    // Then
    verify(notificationRepository).findPendingForDigest(
        eq(DeliveryMode.DIGEST), eq(DeliveryMode.REALTIME), eq(NotificationStatus.PENDING), any(Instant.class), any());
  }

  // -------------------------------------------------------------------------
  // sendDaily — batch selection
  // -------------------------------------------------------------------------

  @Test
  void should_do_nothing_when_daily_batch_empty() {
    // Given
    when(notificationRepository.findPendingByDeliveryMode(any(), any(), any())).thenReturn(List.of());

    // When
    sender.sendDaily();

    // Then
    verify(chatResolver, never()).resolveChatId(any());
  }

  @Test
  void should_request_only_daily_deliveries_from_repository() {
    // Given
    when(notificationRepository.findPendingByDeliveryMode(any(), any(), any())).thenReturn(List.of());

    // When
    sender.sendDaily();

    // Then
    verify(notificationRepository).findPendingByDeliveryMode(eq(DeliveryMode.DAILY), eq(NotificationStatus.PENDING), any());
  }

  // -------------------------------------------------------------------------
  // grouping and happy path
  // -------------------------------------------------------------------------

  @Test
  void should_send_one_message_grouping_all_notifications_of_same_user() throws Exception {
    // Given
    var user = buildUser(1L);
    var n1 = buildNotification(user, DeliveryMode.DIGEST, TriggerType.NEW_LISTING);
    var n2 = buildNotification(user, DeliveryMode.DIGEST, TriggerType.PRICE_DROP);
    mockDigestBatch(n1, n2);
    mockChatId(user, "111222333");
    mockDigestLine(n1, "line-1");
    mockDigestLine(n2, "line-2");
    when(telegramClient.execute(any(SendMessage.class))).thenReturn(mock());

    // When
    sender.sendDigest();

    // Then
    var captor = ArgumentCaptor.forClass(SendMessage.class);
    verify(telegramClient, times(1)).execute(captor.capture());
    assertThat(captor.getValue().getText()).contains("line-1").contains("line-2");
  }

  @Test
  void should_send_separate_messages_when_batch_has_multiple_users() throws Exception {
    // Given
    var user1 = buildUser(1L);
    var user2 = buildUser(2L);
    var n1 = buildNotification(user1, DeliveryMode.DIGEST, TriggerType.NEW_LISTING);
    var n2 = buildNotification(user2, DeliveryMode.DIGEST, TriggerType.NEW_LISTING);
    mockDigestBatch(n1, n2);
    mockChatId(user1, "111");
    mockChatId(user2, "222");
    mockDigestLine(n1, "line-1");
    mockDigestLine(n2, "line-2");
    when(telegramClient.execute(any(SendMessage.class))).thenReturn(mock());

    // When
    sender.sendDigest();

    // Then
    verify(telegramClient, times(2)).execute(any(SendMessage.class));
  }

  @Test
  void should_mark_notifications_sent_when_delivery_succeeds() throws Exception {
    // Given
    var user = buildUser(1L);
    var notification = buildNotification(user, DeliveryMode.DIGEST, TriggerType.NEW_LISTING);
    mockDigestBatch(notification);
    mockChatId(user, "111222333");
    mockDigestLine(notification, "line-1");
    when(telegramClient.execute(any(SendMessage.class))).thenReturn(mock());

    // When
    sender.sendDigest();

    // Then
    verify(statusUpdater).markSent(notification);
  }

  // -------------------------------------------------------------------------
  // failure handling
  // -------------------------------------------------------------------------

  @Test
  void should_mark_all_user_notifications_failed_when_no_telegram_account_linked() throws Exception {
    // Given
    var user = buildUser(1L);
    var n1 = buildNotification(user, DeliveryMode.DIGEST, TriggerType.NEW_LISTING);
    var n2 = buildNotification(user, DeliveryMode.DIGEST, TriggerType.PRICE_DROP);
    mockDigestBatch(n1, n2);
    when(chatResolver.resolveChatId(user)).thenReturn(Optional.empty());

    // When
    sender.sendDigest();

    // Then
    verify(statusUpdater).markFailed(n1);
    verify(statusUpdater).markFailed(n2);
    verify(telegramClient, never()).execute(any(SendMessage.class));
  }

  @Test
  void should_mark_all_user_notifications_failed_when_telegram_api_throws() throws Exception {
    // Given
    var user = buildUser(1L);
    var notification = buildNotification(user, DeliveryMode.DIGEST, TriggerType.NEW_LISTING);
    mockDigestBatch(notification);
    mockChatId(user, "111222333");
    mockDigestLine(notification, "line-1");
    when(telegramClient.execute(any(SendMessage.class))).thenThrow(new TelegramApiException("network error"));

    // When
    sender.sendDigest();

    // Then
    verify(statusUpdater).markFailed(notification);
  }

  @Test
  void should_skip_only_broken_notification_when_one_fails_to_format() throws Exception {
    // Given — a batch of two, one throws while formatting; the other must still be delivered
    var user = buildUser(1L);
    var broken = buildNotification(user, DeliveryMode.DIGEST, TriggerType.NEW_LISTING);
    var healthy = buildNotification(user, DeliveryMode.DIGEST, TriggerType.PRICE_DROP);
    mockDigestBatch(broken, healthy);
    mockChatId(user, "111222333");
    when(listingMapper.toSummaryResponse(broken.getListing())).thenThrow(new RuntimeException("mapping error"));
    mockDigestLine(healthy, "line-healthy");
    when(telegramClient.execute(any(SendMessage.class))).thenReturn(mock());

    // When
    sender.sendDigest();

    // Then
    verify(statusUpdater).markFailed(broken);
    verify(statusUpdater).markSent(healthy);
    var captor = ArgumentCaptor.forClass(SendMessage.class);
    verify(telegramClient).execute(captor.capture());
    assertThat(captor.getValue().getText()).contains("line-healthy");
  }

  @Test
  void should_not_send_message_when_every_notification_in_batch_fails_to_format() throws Exception {
    // Given
    var user = buildUser(1L);
    var notification = buildNotification(user, DeliveryMode.DIGEST, TriggerType.NEW_LISTING);
    mockDigestBatch(notification);
    mockChatId(user, "111222333");
    when(listingMapper.toSummaryResponse(notification.getListing())).thenThrow(new RuntimeException("mapping error"));

    // When
    sender.sendDigest();

    // Then
    verify(statusUpdater).markFailed(notification);
    verify(telegramClient, never()).execute(any(SendMessage.class));
  }

  @Test
  void should_leave_overflow_notifications_pending_when_digest_text_exceeds_length_limit() throws Exception {
    // Given — 15 notifications of 400 chars each (6000 total) exceed Telegram's 4096 limit
    var user = buildUser(1L);
    var notifications = new Notification[15];
    for (int i = 0; i < notifications.length; i++) {
      notifications[i] = buildNotification(user, DeliveryMode.DIGEST, TriggerType.NEW_LISTING);
      mockDigestLine(notifications[i], "x".repeat(400));
    }
    mockDigestBatch(notifications);
    mockChatId(user, "111222333");
    when(telegramClient.execute(any(SendMessage.class))).thenReturn(mock());

    // When
    sender.sendDigest();

    // Then
    var captor = ArgumentCaptor.forClass(SendMessage.class);
    verify(telegramClient).execute(captor.capture());
    assertThat(captor.getValue().getText()).hasSizeLessThanOrEqualTo(4096).contains("и ещё");
    verify(statusUpdater, atMost(notifications.length - 1)).markSent(any());
  }

  // -------------------------------------------------------------------------
  // helpers
  // -------------------------------------------------------------------------

  private void mockDigestBatch(Notification... notifications) {
    when(notificationRepository.findPendingForDigest(any(), any(), any(), any(Instant.class), any(Pageable.class)))
        .thenReturn(List.of(notifications));
  }

  private void mockChatId(User user, String chatId) {
    when(chatResolver.resolveChatId(user)).thenReturn(Optional.of(chatId));
  }

  private void mockDigestLine(Notification notification, String line) {
    var summary = mock(ListingSummaryResponse.class);
    when(listingMapper.toSummaryResponse(notification.getListing())).thenReturn(summary);
    when(listingFormatter.buildDigestLine(eq(notification.getTriggerType()), eq(summary))).thenReturn(line);
  }

  private User buildUser(Long id) {
    var user = new User();
    user.setId(id);
    user.setDisplayName("Test User " + id);
    user.setActive(true);
    return user;
  }

  private Notification buildNotification(User user, DeliveryMode deliveryMode, TriggerType triggerType) {
    var subscription = new Subscription();
    subscription.setId(1L);
    subscription.setUser(user);
    subscription.setDeliveryMode(deliveryMode);

    var listing = new Listing();
    listing.setId(System.nanoTime());

    var notification = new Notification();
    notification.setId(System.nanoTime());
    notification.setSubscription(subscription);
    notification.setListing(listing);
    notification.setTriggerType(triggerType);
    notification.setStatus(NotificationStatus.PENDING);
    notification.setCreatedAt(Instant.now());
    return notification;
  }
}
