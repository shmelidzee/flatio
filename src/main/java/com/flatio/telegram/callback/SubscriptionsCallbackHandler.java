package com.flatio.telegram.callback;

import com.flatio.common.util.TelegramHtmlEscaper;
import com.flatio.domain.subscription.DeliveryMode;
import com.flatio.service.SubscriptionService;
import com.flatio.service.UserService;
import com.flatio.telegram.keyboard.MainMenuKeyboardFactory;
import com.flatio.web.dto.SubscriptionResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;

/**
 * Handles the {@code action:subscriptions} callback — opens the "Мои подписки" section
 * (issue #456).
 *
 * <p>Reuses {@link SubscriptionService}, the same service backing the REST
 * {@code /api/v1/subscriptions} endpoint (Telegram and REST API share services, per project
 * architecture rules). Shows a read-only summary of the user's subscriptions; per-item management
 * (pause, resume, edit, delete) is out of scope for this issue and left to a follow-up (#TBD-SUB).
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class SubscriptionsCallbackHandler {

  /** Callback data value that opens the subscriptions section. */
  public static final String ACTION_SUBSCRIPTIONS = MainMenuKeyboardFactory.ACTION_SUBSCRIPTIONS;

  private static final int PAGE_SIZE = 10;
  private static final String HEADER = "🔔 <b>Мои подписки</b>\n\n";
  private static final String EMPTY_TEXT = "🔔 У вас пока нет подписок на поиск.";

  private final UserService userService;
  private final SubscriptionService subscriptionService;
  private final MainMenuKeyboardFactory keyboardFactory;

  /**
   * Builds the subscriptions list reply for the given callback.
   *
   * @param callbackQuery the incoming callback query, never null
   * @return SendMessage listing the user's subscriptions, never null
   */
  public SendMessage handle(CallbackQuery callbackQuery) {
    Long telegramId = callbackQuery.getFrom().getId();
    String chatId = String.valueOf(callbackQuery.getMessage().getChatId());

    var user = userService.findByTelegramId(telegramId);
    if (user.isEmpty()) {
      log.warn("action:subscriptions callback from unregistered telegramId={}", telegramId);
      return buildMessage(chatId, EMPTY_TEXT);
    }

    var pageable = PageRequest.of(0, PAGE_SIZE, Sort.by(Sort.Direction.DESC, "createdAt"));
    var page = subscriptionService.findByUser(user.get().getId(), pageable);
    log.debug("Subscriptions list requested: telegramId={}, count={}", telegramId, page.getNumberOfElements());

    String text = page.isEmpty() ? EMPTY_TEXT : HEADER + formatItems(page.getContent());
    return buildMessage(chatId, text);
  }

  private String formatItems(List<SubscriptionResponse> items) {
    var sb = new StringBuilder();
    int index = 1;
    for (var item : items) {
      sb.append(index++).append(". ").append(TelegramHtmlEscaper.escapeHtml(item.name()))
          .append(" — ").append(statusLabel(item.active()))
          .append(" (").append(deliveryModeLabel(item.deliveryMode())).append(")\n");
    }
    return sb.toString();
  }

  private String statusLabel(boolean active) {
    return active ? "активна" : "на паузе";
  }

  private String deliveryModeLabel(DeliveryMode deliveryMode) {
    if (deliveryMode == null) {
      return "—";
    }
    return switch (deliveryMode) {
      case REALTIME -> "мгновенно";
      case DIGEST -> "дайджест";
      case DAILY -> "раз в день";
    };
  }

  private SendMessage buildMessage(String chatId, String text) {
    return SendMessage.builder()
        .chatId(chatId)
        .text(text)
        .parseMode("HTML")
        .replyMarkup(keyboardFactory.buildBackToMenu())
        .build();
  }
}
