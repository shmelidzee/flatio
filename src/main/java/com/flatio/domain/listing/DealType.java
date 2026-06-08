package com.flatio.domain.listing;

/** Type of real estate deal. */
public enum DealType {
  RENT,
  SELL,
  RENT_DAILY;

  /**
   * Returns true if the given string maps to a known {@code DealType} value.
   *
   * <p>Comparison is case-insensitive. Null input returns false.
   *
   * @param value the raw string from a connector, may be null
   * @return true if value is a recognised deal type
   */
  public static boolean isKnown(String value) {
    if (value == null) {
      return false;
    }
    try {
      valueOf(value.toUpperCase());
      return true;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }
}
