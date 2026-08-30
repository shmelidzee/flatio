package com.flatio.common.util;

import java.time.LocalTime;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QuietHoursEvaluatorTest {

  // -------------------------------------------------------------------------
  // window wrapping around midnight (e.g. 23:00-07:00)
  // -------------------------------------------------------------------------

  @Test
  void should_return_true_when_now_is_after_midnight_inside_wrapping_window() {
    // Given
    var start = LocalTime.of(23, 0);
    var end = LocalTime.of(7, 0);
    var now = LocalTime.of(0, 30);

    // When
    var result = QuietHoursEvaluator.isWithinQuietHours(start, end, now);

    // Then
    assertThat(result).isTrue();
  }

  @Test
  void should_return_false_when_now_is_after_wrapping_window_end() {
    // Given
    var start = LocalTime.of(23, 0);
    var end = LocalTime.of(7, 0);
    var now = LocalTime.of(8, 0);

    // When
    var result = QuietHoursEvaluator.isWithinQuietHours(start, end, now);

    // Then
    assertThat(result).isFalse();
  }

  @Test
  void should_return_true_when_now_equals_wrapping_window_start() {
    // Given — half-open interval [start, end) includes start
    var start = LocalTime.of(23, 0);
    var end = LocalTime.of(7, 0);
    var now = LocalTime.of(23, 0);

    // When
    var result = QuietHoursEvaluator.isWithinQuietHours(start, end, now);

    // Then
    assertThat(result).isTrue();
  }

  @Test
  void should_return_false_when_now_equals_wrapping_window_end() {
    // Given — half-open interval [start, end) excludes end
    var start = LocalTime.of(23, 0);
    var end = LocalTime.of(7, 0);
    var now = LocalTime.of(7, 0);

    // When
    var result = QuietHoursEvaluator.isWithinQuietHours(start, end, now);

    // Then
    assertThat(result).isFalse();
  }

  // -------------------------------------------------------------------------
  // window not wrapping around midnight (e.g. 22:00-23:00)
  // -------------------------------------------------------------------------

  @Test
  void should_return_true_when_now_is_inside_non_wrapping_window() {
    // Given
    var start = LocalTime.of(22, 0);
    var end = LocalTime.of(23, 0);
    var now = LocalTime.of(22, 30);

    // When
    var result = QuietHoursEvaluator.isWithinQuietHours(start, end, now);

    // Then
    assertThat(result).isTrue();
  }

  @Test
  void should_return_false_when_now_is_before_non_wrapping_window() {
    // Given
    var start = LocalTime.of(22, 0);
    var end = LocalTime.of(23, 0);
    var now = LocalTime.of(21, 0);

    // When
    var result = QuietHoursEvaluator.isWithinQuietHours(start, end, now);

    // Then
    assertThat(result).isFalse();
  }

  // -------------------------------------------------------------------------
  // quiet hours not configured / degenerate window
  // -------------------------------------------------------------------------

  @Test
  void should_return_false_when_start_is_null() {
    // Given
    var end = LocalTime.of(7, 0);
    var now = LocalTime.of(1, 0);

    // When
    var result = QuietHoursEvaluator.isWithinQuietHours(null, end, now);

    // Then
    assertThat(result).isFalse();
  }

  @Test
  void should_return_false_when_end_is_null() {
    // Given
    var start = LocalTime.of(23, 0);
    var now = LocalTime.of(1, 0);

    // When
    var result = QuietHoursEvaluator.isWithinQuietHours(start, null, now);

    // Then
    assertThat(result).isFalse();
  }

  @Test
  void should_return_false_when_both_bounds_are_null() {
    // Given
    var now = LocalTime.of(1, 0);

    // When
    var result = QuietHoursEvaluator.isWithinQuietHours(null, null, now);

    // Then
    assertThat(result).isFalse();
  }

  @Test
  void should_return_false_when_start_equals_end() {
    // Given — degenerate empty interval
    var start = LocalTime.of(10, 0);
    var end = LocalTime.of(10, 0);
    var now = LocalTime.of(10, 0);

    // When
    var result = QuietHoursEvaluator.isWithinQuietHours(start, end, now);

    // Then
    assertThat(result).isFalse();
  }
}
