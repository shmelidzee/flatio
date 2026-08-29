package com.flatio.common.util;

import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;

/**
 * Restricts Telegram bot sections that return personal user data to private one-on-one chats
 * (issue #463, follow-up to #456).
 *
 * <p>{@code FlatioBot} does not filter any callback handler by chat type — this is a
 * pre-existing pattern shared by the whole bot navigation. Issue #456 was the first to expose
 * personal data (favorites, subscriptions, blacklist) through that mechanism: a callback answered
 * in a group chat would deliver the requesting user's own data into that group, visible to every
 * member able to read it. Ownership of the data itself was already verified correct in #456 —
 * the risk here is showing correct data in a non-private context, not leaking another user's data.
 */
public final class TelegramPrivateChatGuard {

  /** Reply shown when a personal-data section is requested outside a private chat. */
  public static final String PRIVATE_CHAT_REQUIRED_TEXT =
      "🔒 Этот раздел содержит личные данные и доступен только в переписке с ботом один на один. "
          + "Откройте бота в личных сообщениях, чтобы посмотреть его.";

  private TelegramPrivateChatGuard() {
  }

  /**
   * Checks whether the callback's chat is a one-on-one conversation with the bot.
   *
   * @param callbackQuery the incoming callback query, never null
   * @return true if the chat type is {@code private}; false for group/supergroup/channel chats
   */
  public static boolean isPrivateChat(CallbackQuery callbackQuery) {
    return isPrivateChat(callbackQuery.getMessage().getChat());
  }

  /**
   * Checks whether a text-command message's chat is a one-on-one conversation with the bot
   * (issue #473 — {@code /favorites}, {@code /subscriptions}, {@code /blacklist} commands).
   *
   * @param message the incoming message carrying a text command, never null
   * @return true if the chat type is {@code private}; false for group/supergroup/channel chats
   */
  public static boolean isPrivateChat(Message message) {
    return isPrivateChat(message.getChat());
  }

  private static boolean isPrivateChat(Chat chat) {
    return Boolean.TRUE.equals(chat.isUserChat());
  }
}
