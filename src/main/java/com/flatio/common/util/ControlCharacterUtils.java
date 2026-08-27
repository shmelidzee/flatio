package com.flatio.common.util;

/**
 * Strips control characters from user-provided free text before it is persisted or logged.
 *
 * <p>Free-text input (e.g. a blacklist keyword) is not otherwise restricted to printable
 * characters — {@code \r}/{@code \n} embedded in it would let a user forge fake log entries when
 * the value is later written to a log line as-is (CWE-117, log forging/injection), and would be
 * meaningless as a literal substring to match against listing text anyway.
 */
public final class ControlCharacterUtils {

  private ControlCharacterUtils() {
  }

  /**
   * Removes ASCII control characters ({@code \p{Cntrl}}: {@code U+0000}–{@code U+001F} and
   * {@code U+007F}, including {@code \r}, {@code \n}, and tab) from the given value.
   *
   * <p>Java's {@code \p{Cntrl}} is the POSIX/ASCII class, not the full Unicode {@code Cc}
   * category — it does not match the C1 control range ({@code U+0080}–{@code U+009F}). Every
   * control character reachable from a standard keyboard or a typical HTTP client is covered;
   * C1 codes are exotic enough that a real client is exceptionally unlikely to send them.
   *
   * @param value the raw user-provided text, must not be null
   * @return the value with all ASCII control characters removed
   */
  public static String stripControlCharacters(String value) {
    return value.replaceAll("\\p{Cntrl}", "");
  }
}
