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
   * Removes all Unicode control characters (category {@code Cntrl}, including {@code \r},
   * {@code \n}, and tab) from the given value.
   *
   * @param value the raw user-provided text, must not be null
   * @return the value with all control characters removed
   */
  public static String stripControlCharacters(String value) {
    return value.replaceAll("\\p{Cntrl}", "");
  }
}
