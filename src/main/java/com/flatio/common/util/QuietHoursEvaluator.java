package com.flatio.common.util;

import java.time.LocalTime;

/**
 * Evaluates whether a given point in time falls inside a subscription's quiet hours window
 * (FR-SUB-7, issue #411).
 *
 * <p>The window is half-open ({@code [start, end)}) and may wrap around midnight, e.g.
 * {@code 23:00–07:00}: {@code now=00:30} is inside the window, {@code now=08:00} is not.
 */
public final class QuietHoursEvaluator {

  private QuietHoursEvaluator() {
  }

  /**
   * Checks whether {@code now} falls within the half-open {@code [start, end)} quiet hours window,
   * correctly handling a window that wraps around midnight (e.g. {@code start=23:00, end=07:00}).
   *
   * @param start quiet hours start, or {@code null} if quiet hours are not configured
   * @param end quiet hours end, or {@code null} if quiet hours are not configured
   * @param now the time to check, never null
   * @return {@code true} if {@code now} is inside the window; {@code false} if quiet hours are not
   *     configured (either bound is {@code null}) or {@code now} is outside the window
   */
  public static boolean isWithinQuietHours(LocalTime start, LocalTime end, LocalTime now) {
    if (start == null || end == null) {
      return false;
    }
    if (start.isBefore(end)) {
      return now.compareTo(start) >= 0 && now.compareTo(end) < 0;
    }
    if (start.isAfter(end)) {
      return now.compareTo(start) >= 0 || now.compareTo(end) < 0;
    }
    return false;
  }
}
