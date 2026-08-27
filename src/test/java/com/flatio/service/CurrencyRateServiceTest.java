package com.flatio.service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CurrencyRateServiceTest {

  private final Map<String, BigDecimal> rates = new HashMap<>();
  private final CurrencyRateService currencyRateService = (base, target) ->
      Optional.ofNullable(rates.get(base + "->" + target));

  @Test
  void should_return_same_amount_when_currencies_are_identical() {
    // Given
    var amount = BigDecimal.valueOf(100);

    // When
    Optional<BigDecimal> result = currencyRateService.convert(amount, "USD", "USD");

    // Then
    assertThat(result).isPresent();
    assertThat(result.get()).isEqualByComparingTo("100.00");
  }

  @Test
  void should_convert_from_byn_to_foreign_currency_using_inverse_rate() {
    // Given — 1 USD = 3.25 BYN
    rates.put("USD->BYN", BigDecimal.valueOf(3.25));

    // When
    Optional<BigDecimal> result = currencyRateService.convert(BigDecimal.valueOf(325), "BYN", "USD");

    // Then
    assertThat(result).isPresent();
    assertThat(result.get()).isEqualByComparingTo("100.00");
  }

  @Test
  void should_convert_from_foreign_currency_to_byn_using_direct_rate() {
    // Given — 1 USD = 3.25 BYN
    rates.put("USD->BYN", BigDecimal.valueOf(3.25));

    // When
    Optional<BigDecimal> result = currencyRateService.convert(BigDecimal.valueOf(100), "USD", "BYN");

    // Then
    assertThat(result).isPresent();
    assertThat(result.get()).isEqualByComparingTo("325.00");
  }

  @Test
  void should_convert_between_two_foreign_currencies_using_byn_as_pivot() {
    // Given — 1 USD = 3.25 BYN, 1 EUR = 3.55 BYN
    rates.put("USD->BYN", BigDecimal.valueOf(3.25));
    rates.put("EUR->BYN", BigDecimal.valueOf(3.55));

    // When — 100 USD -> BYN -> EUR
    Optional<BigDecimal> result = currencyRateService.convert(BigDecimal.valueOf(100), "USD", "EUR");

    // Then
    assertThat(result).isPresent();
    assertThat(result.get()).isEqualByComparingTo(
        BigDecimal.valueOf(100).multiply(BigDecimal.valueOf(3.25))
            .divide(BigDecimal.valueOf(3.55), 2, java.math.RoundingMode.HALF_UP));
  }

  @Test
  void should_return_empty_when_rate_for_byn_pivot_is_missing() {
    // Given — no rate recorded for GBP
    // When
    Optional<BigDecimal> result = currencyRateService.convert(BigDecimal.valueOf(100), "GBP", "BYN");

    // Then
    assertThat(result).isEmpty();
  }

  @Test
  void should_return_empty_when_second_leg_rate_is_missing_for_cross_currency_conversion() {
    // Given — USD rate known, EUR rate missing
    rates.put("USD->BYN", BigDecimal.valueOf(3.25));

    // When
    Optional<BigDecimal> result = currencyRateService.convert(BigDecimal.valueOf(100), "USD", "EUR");

    // Then
    assertThat(result).isEmpty();
  }

  @Test
  void should_default_usd_to_byn_to_delegating_get_rate() {
    // Given
    rates.put("USD->BYN", BigDecimal.valueOf(3.25));

    // When
    Optional<BigDecimal> result = currencyRateService.getUsdToByn();

    // Then
    assertThat(result).isPresent();
    assertThat(result.get()).isEqualByComparingTo("3.25");
  }
}
