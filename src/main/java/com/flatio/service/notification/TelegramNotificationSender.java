package com.flatio.service.notification;

import com.flatio.config.NotificationDeliveryProperties;
import com.flatio.domain.notification.Notification;
import com.flatio.domain.notification.NotificationStatus;
import com.flatio.domain.subscription.DeliveryMode;
import com.flatio.domain.subscription.TriggerType;
import com.flatio.domain.user.AuthProvider;
import com.flatio.domain.user.User;
import com.flatio.domain.user.UserAuthProvider;
import com.flatio.repository.NotificationRepository;
import com.flatio.repository.UserAuthProviderRepository;
import com.flatio.telegram.formatter.ListingFormatter;
import com.flatio.telegram.handler.PhotoProxyClient;
import com.flatio.web.dto.ListingSummaryResponse;
import com.flatio.web.mapper.ListingMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

/**
 * Delivers {@code PENDING} notifications to their subscription's owner over Telegram
 * (REALTIME delivery mode only — DIGEST/DAILY batching is issue #410).
 *
 * <p>Each notification is delivered and its status updated independently, so one failure does
 * not affect the rest of the batch. A per-user hourly cap ({@link NotificationDeliveryProperties
 * #maxPerHour}) enforces FR-SUB-9: once reached, the user's remaining {@code PENDING}
 * notifications are left unsent for this run rather than delivered — issue #410's digest
 * mechanism is the intended home for whatever a user's real-time quota cannot cover.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class TelegramNotificationSender {

  private static final Duration RATE_LIMIT_WINDOW = Duration.ofHours(1);
  private static final int CAPTION_MAX_LENGTH = 1024;

  private static final Map<TriggerType, String> TRIGGER_LABELS = Map.of(
      TriggerType.NEW_LISTING, "🆕 Новое объявление по вашей подписке",
      TriggerType.PRICE_DROP, "📉 Цена снижена",
      TriggerType.REPOSTED, "🔁 Объявление опубликовано повторно",
      TriggerType.REACTIVATED, "✅ Объявление снова активно"
  );

  private final NotificationRepository notificationRepository;
  private final UserAuthProviderRepository userAuthProviderRepository;
  private final ListingMapper listingMapper;
  private final ListingFormatter listingFormatter;
  private final PhotoProxyClient photoProxyClient;
  private final TelegramClient telegramClient;
  private final NotificationDeliveryProperties properties;
  private final MeterRegistry meterRegistry;

  /**
   * Selects a batch of sendable notifications and attempts to deliver each REALTIME one,
   * skipping any subscription whose delivery mode is not REALTIME (left {@code PENDING} for
   * issue #410) and any user who has already reached their hourly delivery cap this run.
   */
  public void sendPending() {
    Instant retryBefore = Instant.now().minus(Duration.ofMinutes(properties.retryDelayMinutes()));
    List<Notification> batch = notificationRepository.findSendable(DeliveryMode.REALTIME,
        NotificationStatus.PENDING, NotificationStatus.FAILED, retryBefore, PageRequest.of(0, properties.batchSize()));
    if (batch.isEmpty()) {
      return;
    }

    Map<Long, Long> sentThisRunByUserId = new HashMap<>();
    int delivered = 0;
    for (Notification notification : batch) {
      if (deliverIfUnderLimit(notification, sentThisRunByUserId)) {
        delivered++;
      }
    }
    log.info("Notification delivery run completed: batchSize={}, delivered={}", batch.size(), delivered);
  }

  private boolean deliverIfUnderLimit(Notification notification, Map<Long, Long> sentThisRunByUserId) {
    User user = notification.getSubscription().getUser();
    long sentCount = sentThisRunByUserId.computeIfAbsent(user.getId(), id -> countRecentlySent(user));
    if (sentCount >= properties.maxPerHour()) {
      log.debug("Skipping notification, hourly delivery cap reached: userId={}, notificationId={}",
          user.getId(), notification.getId());
      return false;
    }
    if (!deliver(notification, user)) {
      return false;
    }
    sentThisRunByUserId.merge(user.getId(), 1L, Long::sum);
    return true;
  }

  private long countRecentlySent(User user) {
    return notificationRepository.countByUserAndStatusAndSentAtAfter(
        user, NotificationStatus.SENT, Instant.now().minus(RATE_LIMIT_WINDOW));
  }

  private boolean deliver(Notification notification, User user) {
    Optional<String> chatId = resolveChatId(user);
    if (chatId.isEmpty()) {
      log.error("No Telegram account linked for user, cannot deliver notification: userId={}, notificationId={}",
          user.getId(), notification.getId());
      markFailed(notification);
      return false;
    }

    ListingSummaryResponse listing = listingMapper.toSummaryResponse(notification.getListing());
    String caption = buildCaption(notification.getTriggerType(), listing);
    InlineKeyboardMarkup keyboard = listingFormatter.buildKeyboard(listing.sourceUrl());
    try {
      sendCard(chatId.get(), caption, keyboard, listing.photoUrl(), listing.id());
      markSent(notification);
      return true;
    } catch (TelegramApiException e) {
      log.error("Failed to deliver notification: notificationId={}, userId={}, error={}",
          notification.getId(), user.getId(), e.getMessage(), e);
      markFailed(notification);
      return false;
    }
  }

  private Optional<String> resolveChatId(User user) {
    return userAuthProviderRepository.findByUserAndProvider(user, AuthProvider.TELEGRAM)
        .map(UserAuthProvider::getExternalId);
  }

  private String buildCaption(TriggerType triggerType, ListingSummaryResponse listing) {
    String label = "<b>" + TRIGGER_LABELS.getOrDefault(triggerType, "🔔 Уведомление по подписке") + "</b>\n\n";
    String combined = label + listingFormatter.buildCaption(listing);
    if (combined.length() <= CAPTION_MAX_LENGTH) {
      return combined;
    }
    return combined.substring(0, CAPTION_MAX_LENGTH - 1) + "…";
  }

  private void sendCard(String chatId, String caption, InlineKeyboardMarkup keyboard, String photoUrl, Long listingId)
      throws TelegramApiException {
    Optional<byte[]> photoBytes = photoUrl != null ? photoProxyClient.download(photoUrl, listingId) : Optional.empty();
    if (photoBytes.isPresent()) {
      telegramClient.execute(SendPhoto.builder()
          .chatId(chatId)
          .photo(new InputFile(new ByteArrayInputStream(photoBytes.get()), "listing.jpg"))
          .caption(caption)
          .parseMode("HTML")
          .replyMarkup(keyboard)
          .build());
      return;
    }
    telegramClient.execute(SendMessage.builder()
        .chatId(chatId)
        .text(caption)
        .parseMode("HTML")
        .replyMarkup(keyboard)
        .build());
  }

  private void markSent(Notification notification) {
    notification.setStatus(NotificationStatus.SENT);
    notification.setSentAt(Instant.now());
    notificationRepository.save(notification);
    meterRegistry.counter("flatio.notifications.sent", "triggerType", notification.getTriggerType().name())
        .increment();
  }

  private void markFailed(Notification notification) {
    notification.setStatus(NotificationStatus.FAILED);
    notificationRepository.save(notification);
    meterRegistry.counter("flatio.notifications.failed", "triggerType", notification.getTriggerType().name())
        .increment();
  }
}
