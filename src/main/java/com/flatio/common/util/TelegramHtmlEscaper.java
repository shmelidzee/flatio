package com.flatio.common.util;

/**
 * Escapes user- or source-provided free text before it is embedded into a Telegram message sent
 * with {@code parseMode("HTML")}.
 *
 * <p>Telegram's HTML subset treats {@code &}, {@code <}, and {@code >} as markup control
 * characters. Text that ends up in a message body — a listing title scraped from an external
 * source, a subscription name, or a blacklist keyword the user typed — is otherwise unrestricted
 * and could break the message markup or, in the worst case, inject an unintended tag. Escaping
 * these three characters is sufficient for Telegram's HTML subset, which has no attributes with
 * quoted values in message text, only plain tags and text nodes.
 *
 * <p>Extracted (issue #456 review) from five identical private copies that had accumulated across
 * {@code ListingFormatter}, {@code FilterKeyboardFactory}, and the Telegram callback handlers —
 * a single shared implementation keeps future escaping-rule changes from silently missing one of
 * the call sites.
 */
public final class TelegramHtmlEscaper {

  private TelegramHtmlEscaper() {
  }

  /**
   * Escapes {@code &}, {@code <}, and {@code >} in the given text for safe use inside a Telegram
   * message sent with {@code parseMode("HTML")}.
   *
   * @param text the raw text to escape, may be null
   * @return the escaped text, or an empty string if {@code text} is null
   */
  public static String escapeHtml(String text) {
    return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }
}
