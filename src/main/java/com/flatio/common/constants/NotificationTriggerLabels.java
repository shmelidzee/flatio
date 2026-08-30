package com.flatio.common.constants;

import com.flatio.domain.subscription.TriggerType;
import java.util.Map;

/**
 * Human-readable Telegram labels for each {@link TriggerType} (FR-SUB-4).
 *
 * <p>Shared between {@code TelegramNotificationSender} (REALTIME, one card per notification) and
 * {@code BatchNotificationSender} (DIGEST/DAILY, one compact line per notification) so the wording
 * for a given event stays identical across delivery modes.
 */
public final class NotificationTriggerLabels {

  private static final Map<TriggerType, String> LABELS = Map.of(
      TriggerType.NEW_LISTING, "🆕 Новое объявление по вашей подписке",
      TriggerType.PRICE_DROP, "📉 Цена снижена",
      TriggerType.REPOSTED, "🔁 Объявление опубликовано повторно",
      TriggerType.REACTIVATED, "✅ Объявление снова активно"
  );

  private static final String DEFAULT_LABEL = "🔔 Уведомление по подписке";

  private NotificationTriggerLabels() {
  }

  /**
   * Resolves the label for the given trigger type.
   *
   * @param triggerType the event that raised the notification, never null
   * @return human-readable label, never null
   */
  public static String label(TriggerType triggerType) {
    return LABELS.getOrDefault(triggerType, DEFAULT_LABEL);
  }
}
