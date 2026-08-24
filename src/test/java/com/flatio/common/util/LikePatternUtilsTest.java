package com.flatio.common.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LikePatternUtilsTest {

  // -------------------------------------------------------------------------
  // escape
  // -------------------------------------------------------------------------

  @Test
  void should_leave_plain_text_unchanged_when_no_special_characters_present() {
    // When
    var result = LikePatternUtils.escape("Минск");

    // Then
    assertThat(result).isEqualTo("Минск");
  }

  @Test
  void should_escape_percent_sign() {
    // When
    var result = LikePatternUtils.escape("100%");

    // Then
    assertThat(result).isEqualTo("100\\%");
  }

  @Test
  void should_escape_underscore() {
    // When
    var result = LikePatternUtils.escape("d_town");

    // Then
    assertThat(result).isEqualTo("d\\_town");
  }

  @Test
  void should_escape_backslash_before_escaping_other_characters() {
    // Given — a literal backslash followed by a percent sign
    // When
    var result = LikePatternUtils.escape("a\\b%c");

    // Then — backslash becomes \\, then % becomes \%; order matters or the escaping
    // characters introduced for % would themselves be mistaken for user backslashes
    assertThat(result).isEqualTo("a\\\\b\\%c");
  }

  @Test
  void should_escape_all_special_characters_together() {
    // When
    var result = LikePatternUtils.escape("100%_off\\sale");

    // Then
    assertThat(result).isEqualTo("100\\%\\_off\\\\sale");
  }

  @Test
  void should_return_empty_string_when_input_is_empty() {
    // When
    var result = LikePatternUtils.escape("");

    // Then
    assertThat(result).isEmpty();
  }

  // -------------------------------------------------------------------------
  // containsPattern
  // -------------------------------------------------------------------------

  @Test
  void should_wrap_escaped_value_with_wildcards() {
    // When
    var result = LikePatternUtils.containsPattern("d_town");

    // Then
    assertThat(result).isEqualTo("%d\\_town%");
  }

  @Test
  void should_wrap_plain_value_with_wildcards_when_no_special_characters() {
    // When
    var result = LikePatternUtils.containsPattern("Минск");

    // Then
    assertThat(result).isEqualTo("%Минск%");
  }
}
