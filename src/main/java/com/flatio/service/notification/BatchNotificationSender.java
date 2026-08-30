package com.flatio.service.notification;

import com.flatio.config.NotificationDailyProperties;
import com.flatio.config.NotificationDigestProperties;
import com.flatio.domain.notification.Notification;
import com.flatio.domain.notification.NotificationStatus;
import com.flatio.domain.subscription.DeliveryMode;
import com.flatio.domain.user.User;
import com.flatio.repository.NotificationRepository;
import com.flatio.telegram.formatter.ListingFormatter;
import com.flatio.web.mapper.ListingMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

/**
 * Delivers {@code PENDING} notifications over Telegram in batches: DIGEST (every few hours, plus
 * REALTIME notifications rate-limited past their overflow window — FR-SUB-9) and DAILY (once a
 * day). Issue #410.
 *
 * <p>Unlike {@link TelegramNotificationSender}, which sends one full card per notification, this
 * groups every user's sendable notifications into a single compact-line message — sending one
 * card per notification in a batch of potentially dozens would flood the chat.
 *
 * <p>One user's delivery failure (no linked Telegram account, or the Telegram API call itself
 * failing) does not affect any other user's batch in the same run; within a user's batch, one
 * notification's formatting failure does not prevent the rest of that user's notifications from
 * being included.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class BatchNotificationSender {

  private static final int TEXT_MAX_LENGTH = 4096;
  private static final String DIGEST_HEADER = "🔔 <b>Дайджест по вашим подпискам</b> — новых уведомлений: %d\n\n";
  private static final String DAILY_HEADER = "📰 <b>Ежедневная подборка по вашим подпискам</b> — уведомлений: %d\n\n";

  private final NotificationRepository notificationRepository;
  private final TelegramChatResolver chatResolver;
  private final NotificationStatusUpdater statusUpdater;
  private final ListingMapper listingMapper;
  private final ListingFormatter listingFormatter;
  private final TelegramClient telegramClient;
  private final NotificationDigestProperties digestProperties;
  private final NotificationDailyProperties dailyProperties;
  private final MeterRegistry meterRegistry;

  private record FormattedBatch(List<Notification> notifications, List<String> lines) {
  }

  /**
   * Runs a DIGEST batch: DIGEST-subscription notifications, plus REALTIME-subscription
   * notifications that have been rate-limited long enough to overflow into the digest.
   */
  public void sendDigest() {
    Instant realtimeOverflowBefore = Instant.now().minus(Duration.ofMinutes(digestProperties.realtimeOverflowMinutes()));
    List<Notification> batch = notificationRepository.findPendingForDigest(
        DeliveryMode.DIGEST, DeliveryMode.REALTIME, NotificationStatus.PENDING,
        realtimeOverflowBefore, PageRequest.of(0, digestProperties.batchSize()));
    deliverGrouped(batch, DIGEST_HEADER, "digest");
  }

  /**
   * Runs the once-daily batch: DAILY-subscription notifications only.
   */
  public void sendDaily() {
    List<Notification> batch = notificationRepository.findPendingByDeliveryMode(
        DeliveryMode.DAILY, NotificationStatus.PENDING, PageRequest.of(0, dailyProperties.batchSize()));
    deliverGrouped(batch, DAILY_HEADER, "daily");
  }

  private void deliverGrouped(List<Notification> batch, String headerTemplate, String metricTag) {
    if (batch.isEmpty()) {
      return;
    }
    Map<User, List<Notification>> byUser = batch.stream()
        .collect(Collectors.groupingBy(n -> n.getSubscription().getUser(), LinkedHashMap::new, Collectors.toList()));
    int usersNotified = 0;
    for (List<Notification> userBatch : byUser.values()) {
      if (deliverToUser(userBatch, headerTemplate, metricTag)) {
        usersNotified++;
      }
    }
    log.info("Batch notification run completed: type={}, batchSize={}, usersInBatch={}, usersNotified={}",
        metricTag, batch.size(), byUser.size(), usersNotified);
  }

  private boolean deliverToUser(List<Notification> notifications, String headerTemplate, String metricTag) {
    User user = notifications.get(0).getSubscription().getUser();
    Optional<String> chatId = chatResolver.resolveChatId(user);
    if (chatId.isEmpty()) {
      log.error("No Telegram account linked for user, cannot deliver batch: userId={}", user.getId());
      notifications.forEach(statusUpdater::markFailed);
      return false;
    }
    FormattedBatch formatted = formatBatch(notifications);
    if (formatted.notifications().isEmpty()) {
      return false;
    }
    return sendAndMark(chatId.get(), formatted, headerTemplate, metricTag);
  }

  private FormattedBatch formatBatch(List<Notification> batch) {
    List<Notification> formattable = new ArrayList<>();
    List<String> lines = new ArrayList<>();
    for (Notification notification : batch) {
      String line = formatLineSafely(notification);
      if (line.isBlank()) {
        statusUpdater.markFailed(notification);
        continue;
      }
      formattable.add(notification);
      lines.add(line);
    }
    return new FormattedBatch(formattable, lines);
  }

  private String formatLineSafely(Notification notification) {
    try {
      var listing = listingMapper.toSummaryResponse(notification.getListing());
      return listingFormatter.buildDigestLine(notification.getTriggerType(), listing);
    } catch (RuntimeException e) {
      log.warn("Failed to format digest line, skipping: notificationId={}", notification.getId(), e);
      return "";
    }
  }

  private record DigestText(String text, int includedCount) {
  }

  /**
   * Sends the digest text and marks only the notifications actually included in it.
   *
   * <p>A notification whose line got cut off by {@link #buildDigestText}'s length limit is left
   * untouched (still {@code PENDING}) either way — it was never shown to the user, so it must not
   * be marked {@code SENT}; the next run picks it up.
   */
  private boolean sendAndMark(String chatId, FormattedBatch formatted, String headerTemplate, String metricTag) {
    DigestText digestText = buildDigestText(formatted.lines(), headerTemplate);
    List<Notification> included = formatted.notifications().subList(0, digestText.includedCount());
    try {
      telegramClient.execute(SendMessage.builder().chatId(chatId).text(digestText.text()).parseMode("HTML").build());
      included.forEach(statusUpdater::markSent);
      meterRegistry.counter("flatio.notifications.batch.messages", "type", metricTag).increment();
      return true;
    } catch (TelegramApiException e) {
      log.error("Failed to deliver batch: count={}, error={}", included.size(), e.getMessage(), e);
      included.forEach(statusUpdater::markFailed);
      return false;
    }
  }

  private DigestText buildDigestText(List<String> lines, String headerTemplate) {
    var sb = new StringBuilder(String.format(headerTemplate, lines.size()));
    int appended = 0;
    for (String line : lines) {
      if (sb.length() + line.length() + 1 > TEXT_MAX_LENGTH) {
        break;
      }
      sb.append(line).append("\n");
      appended++;
    }
    if (appended < lines.size()) {
      sb.append("… и ещё ").append(lines.size() - appended).append(" объявлений");
    }
    return new DigestText(sb.toString().strip(), appended);
  }
}
