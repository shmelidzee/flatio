package com.flatio.domain.listing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DealTypeTest {

  // -------------------------------------------------------------------------
  // isKnown — static helper
  // -------------------------------------------------------------------------

  @Test
  void should_return_true_for_all_known_deal_types() {
    // When / Then
    assertThat(DealType.isKnown("RENT")).isTrue();
    assertThat(DealType.isKnown("SELL")).isTrue();
    assertThat(DealType.isKnown("RENT_DAILY")).isTrue();
  }

  @Test
  void should_return_true_for_known_deal_type_in_lowercase() {
    // When / Then — Onliner sends lowercase; isKnown must be case-insensitive
    assertThat(DealType.isKnown("rent")).isTrue();
    assertThat(DealType.isKnown("sell")).isTrue();
    assertThat(DealType.isKnown("rent_daily")).isTrue();
  }

  @Test
  void should_return_true_for_known_deal_type_in_mixed_case() {
    // When / Then
    assertThat(DealType.isKnown("Rent")).isTrue();
    assertThat(DealType.isKnown("Sell")).isTrue();
    assertThat(DealType.isKnown("Rent_Daily")).isTrue();
  }

  @Test
  void should_return_false_for_null() {
    // When / Then
    assertThat(DealType.isKnown(null)).isFalse();
  }

  @Test
  void should_return_false_for_unknown_string() {
    // When / Then
    assertThat(DealType.isKnown("AUCTION")).isFalse();
    assertThat(DealType.isKnown("EXCHANGE")).isFalse();
    assertThat(DealType.isKnown("")).isFalse();
    assertThat(DealType.isKnown("daily_rent")).isFalse();
  }
}
