package com.flatio.common.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ControlCharacterUtilsTest {

  @Test
  void should_remove_carriage_return_and_line_feed_when_present() {
    // Given
    var value = "foo\r\nbar";

    // When
    var result = ControlCharacterUtils.stripControlCharacters(value);

    // Then
    assertThat(result).isEqualTo("foobar");
  }

  @Test
  void should_remove_tab_when_present_between_words() {
    // Given
    var value = "before\tafter";

    // When
    var result = ControlCharacterUtils.stripControlCharacters(value);

    // Then
    assertThat(result).isEqualTo("beforeafter");
  }

  @Test
  void should_return_unchanged_value_when_no_control_characters_present() {
    // Given
    var value = "novostroyka";

    // When
    var result = ControlCharacterUtils.stripControlCharacters(value);

    // Then
    assertThat(result).isEqualTo("novostroyka");
  }

  @Test
  void should_return_empty_string_when_value_is_only_control_characters() {
    // Given
    var value = "\r\n\t";

    // When
    var result = ControlCharacterUtils.stripControlCharacters(value);

    // Then
    assertThat(result).isEmpty();
  }
}
