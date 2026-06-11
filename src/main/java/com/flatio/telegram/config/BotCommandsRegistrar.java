package com.flatio.telegram.config;

import jakarta.annotation.PostConstruct;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

/**
 * Registers bot commands with Telegram at application startup.
 *
 * <p>Enables the «Меню» button in the Telegram client so users can see
 * available commands without typing them manually.
 * Registration failures are logged as warnings and do not block startup.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class BotCommandsRegistrar {

  private final TelegramClient telegramClient;

  /**
   * Sends the list of bot commands to Telegram via {@code setMyCommands}.
   *
   * <p>Called once after the Spring context is initialized. Errors are non-fatal
   * — the bot operates without the menu button if Telegram is temporarily unavailable.
   */
  @PostConstruct
  public void register() {
    var commands = List.of(
        new BotCommand("start", "Начать работу с ботом"),
        new BotCommand("search", "Поиск объявлений с фильтрами"),
        new BotCommand("help", "Список доступных команд")
    );
    try {
      telegramClient.execute(SetMyCommands.builder().commands(commands).build());
      log.info("Bot menu commands registered: count={}", commands.size());
    } catch (TelegramApiException e) {
      log.warn("Failed to register bot commands — menu button will be unavailable: {}", e.getMessage());
    }
  }
}
