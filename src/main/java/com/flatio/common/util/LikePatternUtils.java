package com.flatio.common.util;

/**
 * Escapes user-provided substrings before they are embedded in a SQL {@code LIKE} pattern.
 *
 * <p>PostgreSQL's {@code LIKE} treats {@code %} and {@code _} as wildcards and {@code \} as its
 * default escape character. Without escaping, a user-typed value containing any of these (e.g.
 * a city named with an underscore, or a keyword the user typed with a stray {@code %}) is
 * silently reinterpreted as a wildcard pattern instead of matched literally — not a SQL injection
 * (queries are already parameterized via JPQL/Criteria API), just an unpredictably wider or
 * narrower match than the user intended (issue #388).
 */
public final class LikePatternUtils {

  /**
   * Escape character used by {@link #escape} and expected by callers building a {@code LIKE}
   * predicate from an escaped pattern.
   *
   * <p>Hibernate 6's Criteria API {@code CriteriaBuilder.like(Expression, String)} always
   * renders an explicit {@code ESCAPE} clause — {@code ESCAPE ''} when no escape character is
   * given, which tells PostgreSQL there is no escape character at all rather than falling back
   * to its own default ({@code \}). Callers using the Criteria API must therefore use the
   * three-argument {@code cb.like(expr, pattern, ESCAPE_CHAR)} overload; the escape clause is
   * implicit (and defaults to {@code \}) for hand-written native SQL {@code LIKE}, so this
   * constant only needs to be passed explicitly on the Criteria API path.
   */
  public static final char ESCAPE_CHAR = '\\';

  private LikePatternUtils() {
  }

  /**
   * Escapes {@code \}, {@code %}, and {@code _} in a value so it matches literally when embedded
   * in a {@code LIKE} pattern. The backslash is escaped first so escaping the other two characters
   * does not itself introduce new, unescaped backslashes.
   *
   * @param value the raw user-provided substring, must not be null
   * @return the value with all {@code LIKE} special characters escaped
   */
  public static String escape(String value) {
    return value
        .replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_");
  }

  /**
   * Builds a {@code LIKE} pattern that matches any string containing {@code value} as a literal
   * substring — equivalent to {@code "%" + value + "%"}, but with {@code value} escaped first so
   * {@code %}/{@code _} in it are matched literally rather than treated as wildcards.
   *
   * @param value the raw user-provided substring, must not be null
   * @return a {@code LIKE} pattern matching {@code value} as a literal substring
   */
  public static String containsPattern(String value) {
    return "%" + escape(value) + "%";
  }
}
