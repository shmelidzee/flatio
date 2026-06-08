package com.flatio.bot;

import com.flatio.service.UserService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

/**
 * Handles the {@code /start} Telegram command.
 *
 * <p>Registers the user on first interaction (or updates {@code lastSeen} on subsequent calls)
 * and replies with a welcome message containing action buttons.
 *
 * <p>Note: registration is always performed regardless of user intent — see OQ-25 for the
 * decision on mandatory vs optional registration.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class StartCommandHandler {

  private final UserService userService;

  /**
   * Processes a {@code /start} update and returns a welcome reply.
   *
   * @param update Telegram update containing the /start command, never null
   * @return SendMessage with welcome text and action buttons, never null
   */
  public SendMessage handle(Update update) {
    var from = update.getMessage().getFrom();
    Long telegramId = from.getId();
    String username = from.getUserName();
    String firstName = from.getFirstName();
    String chatId = String.valueOf(update.getMessage().getChatId());

    // TODO OQ-25: registration is always mandatory until OQ-25 is resolved
    userService.findOrCreate(telegramId, username, firstName);
    log.debug("Handled /start: telegramId={}, chatId={}", telegramId, chatId);

    return buildWelcomeMessage(chatId, firstName);
  }

  private SendMessage buildWelcomeMessage(String chatId, String firstName) {
    String greeting = firstName != null && !firstName.isBlank()
        ? "Привет, " + firstName + "!"
        : "Привет!";

    var searchButton = InlineKeyboardButton.builder()
        .text("Искать")
        .callbackData("action:search")
        .build();
    var helpButton = InlineKeyboardButton.builder()
        .text("Помощь")
        .callbackData("action:help")
        .build();

    var keyboard = InlineKeyboardMarkup.builder()
        .keyboardRow(List.of(searchButton, helpButton))
        .build();

    return SendMessage.builder()
        .chatId(chatId)
        .text(greeting + "\n\nДобро пожаловать в Flatio — агрегатор объявлений о недвижимости.")
        .replyMarkup(keyboard)
        .build();
  }
}
