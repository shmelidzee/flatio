package com.flatio.telegram.command;

import com.flatio.common.util.TelegramPrivateChatGuard;
import com.flatio.telegram.callback.FavoritesCallbackHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Update;

/**
 * Handles the {@code /favorites} Telegram command (issue #473).
 *
 * <p>Opens the same «⭐ Избранное» section as the {@code action:favorites} main-menu button,
 * reusing {@link FavoritesCallbackHandler} — no new business logic.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class FavoritesCommandHandler {

  private final FavoritesCallbackHandler favoritesCallbackHandler;

  /**
   * Processes a {@code /favorites} command update.
   *
   * @param update Telegram update containing the /favorites command, never null
   */
  public void handle(Update update) {
    var message = update.getMessage();
    Long telegramId = message.getFrom().getId();
    String chatId = String.valueOf(message.getChatId());
    log.debug("Handled /favorites: telegramId={}, chatId={}", telegramId, chatId);
    favoritesCallbackHandler.handleCommand(telegramId, chatId, TelegramPrivateChatGuard.isPrivateChat(message));
  }
}
