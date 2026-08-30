package com.flatio.service.notification;

import com.flatio.config.NotificationDeliveryProperties;
import com.flatio.domain.listing.Listing;
import com.flatio.domain.notification.Notification;
import com.flatio.domain.notification.NotificationStatus;
import com.flatio.domain.subscription.DeliveryMode;
import com.flatio.domain.subscription.Subscription;
import com.flatio.domain.subscription.TriggerType;
import com.flatio.domain.user.User;
import com.flatio.repository.NotificationRepository;
import com.flatio.telegram.formatter.ListingFormatter;
import com.flatio.telegram.handler.PhotoProxyClient;
import com.flatio.web.dto.ListingSummaryResponse;
import com.flatio.web.mapper.ListingMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TelegramNotificationSenderTest {

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
  private PhotoProxyClient photoProxyClient;

  @Mock
  private TelegramClient telegramClient;

  private TelegramNotificationSender sender;

  @BeforeEach
  void setUp() {
    var properties = new NotificationDeliveryProperties(50, 10, 5);
    sender = new TelegramNotificationSender(
        notificationRepository, chatResolver, statusUpdater, listingMapper, listingFormatter,
        photoProxyClient, telegramClient, properties
    );
  }

  // -------------------------------------------------------------------------
  // sendPending — batch selection
  // -------------------------------------------------------------------------

  @Test
  void should_do_nothing_when_no_sendable_notifications() {
    // Given
    when(notificationRepository.findSendable(eq(DeliveryMode.REALTIME), eq(NotificationStatus.PENDING),
        eq(NotificationStatus.FAILED), any(Instant.class), any(Pageable.class))).thenReturn(List.of());

    // When
    sender.sendPending();

    // Then
    verify(statusUpdater, never()).markSent(any());
    verify(statusUpdater, never()).markFailed(any());
  }

  @Test
  void should_request_only_realtime_deliveries_from_repository() {
    // Given — DIGEST/DAILY notifications are batched separately (BatchNotificationSender);
    // filtering them out at the query level (not in this class) keeps a DIGEST/DAILY backlog
    // from starving REALTIME delivery out of the batch
    when(notificationRepository.findSendable(any(), any(), any(), any(), any())).thenReturn(List.of());

    // When
    sender.sendPending();

    // Then
    verify(notificationRepository).findSendable(eq(DeliveryMode.REALTIME), any(), any(), any(), any());
  }

  // -------------------------------------------------------------------------
  // sendPending — happy path
  // -------------------------------------------------------------------------

  @Test
  void should_send_message_when_listing_has_no_photo() throws Exception {
    // Given
    var user = buildUser(1L);
    var notification = buildNotification(user, DeliveryMode.REALTIME, TriggerType.NEW_LISTING);
    mockBatch(notification);
    mockChatId(user, "111222333");
    mockListingSummary(notification, null);
    when(listingFormatter.buildCaption(any())).thenReturn("caption");
    when(listingFormatter.buildKeyboard(anyString())).thenReturn(mock(InlineKeyboardMarkup.class));
    when(telegramClient.execute(any(SendMessage.class))).thenReturn(mock());

    // When
    sender.sendPending();

    // Then
    verify(telegramClient).execute(any(SendMessage.class));
    verify(telegramClient, never()).execute(any(SendPhoto.class));
  }

  @Test
  void should_send_photo_when_listing_has_photo_and_download_succeeds() throws Exception {
    // Given
    var user = buildUser(1L);
    var notification = buildNotification(user, DeliveryMode.REALTIME, TriggerType.NEW_LISTING);
    mockBatch(notification);
    mockChatId(user, "111222333");
    mockListingSummary(notification, "https://cdn.example.com/photo.jpg");
    when(photoProxyClient.download(eq("https://cdn.example.com/photo.jpg"), anyLong()))
        .thenReturn(Optional.of(new byte[]{1, 2, 3}));
    when(listingFormatter.buildCaption(any())).thenReturn("caption");
    when(listingFormatter.buildKeyboard(anyString())).thenReturn(mock(InlineKeyboardMarkup.class));
    when(telegramClient.execute(any(SendPhoto.class))).thenReturn(mock());

    // When
    sender.sendPending();

    // Then
    verify(telegramClient).execute(any(SendPhoto.class));
    verify(telegramClient, never()).execute(any(SendMessage.class));
  }

  @Test
  void should_mark_notification_sent_when_delivery_succeeds() throws Exception {
    // Given
    var user = buildUser(1L);
    var notification = buildNotification(user, DeliveryMode.REALTIME, TriggerType.NEW_LISTING);
    mockBatch(notification);
    mockChatId(user, "111222333");
    mockListingSummary(notification, null);
    when(listingFormatter.buildCaption(any())).thenReturn("caption");
    when(listingFormatter.buildKeyboard(anyString())).thenReturn(mock(InlineKeyboardMarkup.class));
    when(telegramClient.execute(any(SendMessage.class))).thenReturn(mock());

    // When
    sender.sendPending();

    // Then
    verify(statusUpdater).markSent(notification);
  }

  // -------------------------------------------------------------------------
  // sendPending — failure handling
  // -------------------------------------------------------------------------

  @Test
  void should_mark_notification_failed_when_telegram_api_throws() throws Exception {
    // Given
    var user = buildUser(1L);
    var notification = buildNotification(user, DeliveryMode.REALTIME, TriggerType.NEW_LISTING);
    mockBatch(notification);
    mockChatId(user, "111222333");
    mockListingSummary(notification, null);
    when(listingFormatter.buildCaption(any())).thenReturn("caption");
    when(listingFormatter.buildKeyboard(anyString())).thenReturn(mock(InlineKeyboardMarkup.class));
    when(telegramClient.execute(any(SendMessage.class))).thenThrow(new TelegramApiException("network error"));

    // When
    sender.sendPending();

    // Then
    verify(statusUpdater).markFailed(notification);
  }

  @Test
  void should_mark_notification_failed_when_no_telegram_account_linked() {
    // Given
    var user = buildUser(1L);
    var notification = buildNotification(user, DeliveryMode.REALTIME, TriggerType.NEW_LISTING);
    mockBatch(notification);
    when(chatResolver.resolveChatId(user)).thenReturn(Optional.empty());

    // When
    sender.sendPending();

    // Then
    verify(statusUpdater).markFailed(notification);
  }

  // -------------------------------------------------------------------------
  // sendPending — hourly rate limit (FR-SUB-9)
  // -------------------------------------------------------------------------

  @Test
  void should_skip_remaining_notifications_for_user_when_hourly_limit_reached() throws Exception {
    // Given — user already at the configured cap (10) for the last hour
    var user = buildUser(1L);
    var notification = buildNotification(user, DeliveryMode.REALTIME, TriggerType.NEW_LISTING);
    mockBatch(notification);
    when(notificationRepository.countByUserAndStatusAndSentAtAfter(eq(user), eq(NotificationStatus.SENT), any()))
        .thenReturn(10L);

    // When
    sender.sendPending();

    // Then
    verify(telegramClient, never()).execute(any(SendMessage.class));
    verify(telegramClient, never()).execute(any(SendPhoto.class));
    verify(statusUpdater, never()).markSent(any());
    verify(statusUpdater, never()).markFailed(any());
  }

  // -------------------------------------------------------------------------
  // helpers
  // -------------------------------------------------------------------------

  private void mockBatch(Notification... notifications) {
    when(notificationRepository.findSendable(eq(DeliveryMode.REALTIME), eq(NotificationStatus.PENDING),
        eq(NotificationStatus.FAILED), any(Instant.class), any(Pageable.class))).thenReturn(List.of(notifications));
  }

  private void mockChatId(User user, String chatId) {
    when(chatResolver.resolveChatId(user)).thenReturn(Optional.of(chatId));
  }

  private void mockListingSummary(Notification notification, String photoUrl) {
    var summary = new ListingSummaryResponse(
        notification.getListing().getId(), "Test listing", BigDecimal.valueOf(1000),
        "BYN", null, null, 2, "APARTMENT", BigDecimal.valueOf(50), "Минск", null,
        "ул. Тестовая, 1", "onliner", Instant.now(), photoUrl,
        "https://source.example.com/listing/1", false
    );
    when(listingMapper.toSummaryResponse(notification.getListing())).thenReturn(summary);
  }

  private User buildUser(Long id) {
    var user = new User();
    user.setId(id);
    user.setDisplayName("Test User");
    user.setActive(true);
    return user;
  }

  private Notification buildNotification(User user, DeliveryMode deliveryMode, TriggerType triggerType) {
    var subscription = new Subscription();
    subscription.setId(1L);
    subscription.setUser(user);
    subscription.setDeliveryMode(deliveryMode);

    var listing = new Listing();
    listing.setId(42L);

    var notification = new Notification();
    notification.setId(7L);
    notification.setSubscription(subscription);
    notification.setListing(listing);
    notification.setTriggerType(triggerType);
    notification.setStatus(NotificationStatus.PENDING);
    return notification;
  }
}
