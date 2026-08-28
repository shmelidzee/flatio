package com.flatio.telegram.callback;

import com.flatio.common.util.TelegramHtmlEscaper;
import com.flatio.common.util.TelegramPrivateChatGuard;
import com.flatio.domain.blacklist.BlacklistEntryType;
import com.flatio.service.BlacklistService;
import com.flatio.service.UserService;
import com.flatio.telegram.keyboard.MainMenuKeyboardFactory;
import com.flatio.web.dto.BlacklistEntryResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;

/**
 * Handles the {@code action:blacklist} callback — opens the "Чёрный список" section
 * (issue #456).
 *
 * <p>Reuses {@link BlacklistService}, the same service backing the REST
 * {@code /api/v1/blacklist} endpoint (Telegram and REST API share services, per project
 * architecture rules). Shows a read-only summary of the user's blacklist entries; per-item
 * management (remove) and adding new entries from the bot is out of scope for this issue and
 * left to a follow-up (#TBD-BL).
 *
 * <p>Restricted to private chats (issue #463): the blacklist is personal data, so a request made
 * from a group/supergroup/channel is answered with a redirect to a private chat instead of the
 * actual list — see {@link TelegramPrivateChatGuard}.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class BlacklistCallbackHandler {

  /** Callback data value that opens the blacklist section. */
  public static final String ACTION_BLACKLIST = MainMenuKeyboardFactory.ACTION_BLACKLIST;

  private static final int PAGE_SIZE = 10;
  private static final String HEADER = "🚫 <b>Чёрный список</b>\n\n";
  private static final String EMPTY_TEXT = "🚫 Ваш чёрный список пока пуст.";

  private final UserService userService;
  private final BlacklistService blacklistService;
  private final MainMenuKeyboardFactory keyboardFactory;

  /**
   * Builds the blacklist list reply for the given callback.
   *
   * @param callbackQuery the incoming callback query, never null
   * @return SendMessage listing the user's blacklist entries, never null
   */
  public SendMessage handle(CallbackQuery callbackQuery) {
    Long telegramId = callbackQuery.getFrom().getId();
    String chatId = String.valueOf(callbackQuery.getMessage().getChatId());

    if (!TelegramPrivateChatGuard.isPrivateChat(callbackQuery)) {
      log.debug("action:blacklist callback rejected outside a private chat: chatId={}", chatId);
      return buildMessage(chatId, TelegramPrivateChatGuard.PRIVATE_CHAT_REQUIRED_TEXT);
    }

    var user = userService.findByTelegramId(telegramId);
    if (user.isEmpty()) {
      log.warn("action:blacklist callback from unregistered telegramId={}", telegramId);
      return buildMessage(chatId, EMPTY_TEXT);
    }

    var pageable = PageRequest.of(0, PAGE_SIZE, Sort.by(Sort.Direction.DESC, "createdAt"));
    var page = blacklistService.findByUser(user.get().getId(), null, pageable);
    log.debug("Blacklist list requested: telegramId={}, count={}", telegramId, page.getNumberOfElements());

    String text = page.isEmpty() ? EMPTY_TEXT : HEADER + formatItems(page.getContent());
    return buildMessage(chatId, text);
  }

  private String formatItems(List<BlacklistEntryResponse> items) {
    var sb = new StringBuilder();
    int index = 1;
    for (var item : items) {
      sb.append(index++).append(". ").append(typeLabel(item.type()))
          .append(": ").append(TelegramHtmlEscaper.escapeHtml(item.value())).append("\n");
    }
    return sb.toString();
  }

  private String typeLabel(BlacklistEntryType type) {
    if (type == null) {
      return "—";
    }
    return switch (type) {
      case LISTING -> "Объявление";
      case SOURCE -> "Источник";
      case KEYWORD -> "Стоп-слово";
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
