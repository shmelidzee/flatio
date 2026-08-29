package com.flatio.telegram.command;

import com.flatio.common.util.TelegramPrivateChatGuard;
import com.flatio.telegram.callback.SubscriptionsCallbackHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Update;

/**
 * Handles the {@code /subscriptions} Telegram command (issue #473).
 *
 * <p>Opens the same «🔔 Мои подписки» section as the {@code action:subscriptions} main-menu
 * button, reusing {@link SubscriptionsCallbackHandler} — no new business logic.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class SubscriptionsCommandHandler {

  private final SubscriptionsCallbackHandler subscriptionsCallbackHandler;

  /**
   * Processes a {@code /subscriptions} command update.
   *
   * @param update Telegram update containing the /subscriptions command, never null
   */
  public void handle(Update update) {
    var message = update.getMessage();
    Long telegramId = message.getFrom().getId();
    String chatId = String.valueOf(message.getChatId());
    log.debug("Handled /subscriptions: telegramId={}, chatId={}", telegramId, chatId);
    subscriptionsCallbackHandler.handleCommand(telegramId, chatId, TelegramPrivateChatGuard.isPrivateChat(message));
  }
}
