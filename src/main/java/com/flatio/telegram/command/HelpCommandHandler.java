package com.flatio.telegram.command;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;

/**
 * Handles the {@code /help} Telegram command and the {@code action:help} callback.
 *
 * <p>Returns a list of available bot commands with short descriptions.
 */
@Component
@Slf4j
public class HelpCommandHandler {

  private static final String HELP_TEXT = """
      📋 <b>Доступные команды</b>

      /start — начало работы с ботом
      /search — поиск объявлений с фильтрами
      /favorites — избранные объявления
      /subscriptions — мои подписки на поиск
      /blacklist — чёрный список
      /help — показать этот список команд""";

  /**
   * Builds a help reply for a {@code /help} text command.
   *
   * @param update Telegram update containing the /help command, never null
   * @return SendMessage with the command list, never null
   */
  public SendMessage handle(Update update) {
    String chatId = String.valueOf(update.getMessage().getChatId());
    log.debug("Handled /help: chatId={}", chatId);
    return buildHelpMessage(chatId);
  }

  /**
   * Builds a help reply for an {@code action:help} inline button callback.
   *
   * @param callbackQuery the incoming callback query, never null
   * @return SendMessage with the command list, never null
   */
  public SendMessage handleCallback(CallbackQuery callbackQuery) {
    String chatId = String.valueOf(callbackQuery.getMessage().getChatId());
    log.debug("Handled action:help callback: chatId={}", chatId);
    return buildHelpMessage(chatId);
  }

  private SendMessage buildHelpMessage(String chatId) {
    return SendMessage.builder()
        .chatId(chatId)
        .text(HELP_TEXT)
        .parseMode("HTML")
        .build();
  }
}
