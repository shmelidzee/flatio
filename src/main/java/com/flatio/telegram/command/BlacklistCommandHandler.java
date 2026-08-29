package com.flatio.telegram.command;

import com.flatio.common.util.TelegramPrivateChatGuard;
import com.flatio.telegram.callback.BlacklistCallbackHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Update;

/**
 * Handles the {@code /blacklist} Telegram command (issue #473).
 *
 * <p>Opens the same «🚫 Чёрный список» section as the {@code action:blacklist} main-menu button,
 * reusing {@link BlacklistCallbackHandler} — no new business logic.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class BlacklistCommandHandler {

  private final BlacklistCallbackHandler blacklistCallbackHandler;

  /**
   * Processes a {@code /blacklist} command update.
   *
   * @param update Telegram update containing the /blacklist command, never null
   */
  public void handle(Update update) {
    var message = update.getMessage();
    Long telegramId = message.getFrom().getId();
    String chatId = String.valueOf(message.getChatId());
    log.debug("Handled /blacklist: telegramId={}, chatId={}", telegramId, chatId);
    blacklistCallbackHandler.handleCommand(telegramId, chatId, TelegramPrivateChatGuard.isPrivateChat(message));
  }
}
