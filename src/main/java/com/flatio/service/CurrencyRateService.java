package com.flatio.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * Provides currency exchange rates and price conversion between currencies known to the
 * platform (BYN, USD, EUR — see {@code flatio.nbrb.currency-ids}).
 *
 * <p>Rates are anchored to BYN: every persisted rate is {@code (foreignCurrency, "BYN")} — how
 * many BYN one unit of the foreign currency is worth, as published by the National Bank of
 * Belarus. {@link #convert} uses BYN as a pivot to support any pair, including two non-BYN
 * currencies (e.g. USD → EUR goes through BYN).
 */
public interface CurrencyRateService {

  /** ISO code of the currency every persisted rate is anchored to. */
  String BYN = "BYN";

  /** Decimal places a converted display price is rounded to. */
  int DISPLAY_SCALE = 2;

  /**
   * Returns the current USD → BYN exchange rate (1 USD in BYN).
   *
   * <p>Returns {@link Optional#empty()} if no rate has ever been recorded for this pair;
   * callers must handle the absent case gracefully.
   *
   * @return USD/BYN rate, or empty if unavailable
   */
  default Optional<BigDecimal> getUsdToByn() {
    return getRate("USD", BYN);
  }

  /**
   * Returns the most recently recorded rate for the given currency pair.
   *
   * <p>Falls back to the last successfully synced rate if today's sync failed or has not run
   * yet (issue #415) — never fails outright just because the most recent sync attempt did.
   *
   * @param baseCurrency   the currency being converted from
   * @param targetCurrency the currency being converted to
   * @return the latest known rate, or empty if this pair has never been recorded
   */
  Optional<BigDecimal> getRate(String baseCurrency, String targetCurrency);

  /**
   * Converts an amount from one currency to another, using BYN as a pivot when neither currency
   * is BYN itself.
   *
   * <p>This is a read-time projection only — it never modifies any stored listing price.
   *
   * @param amount       the amount to convert, in {@code fromCurrency}
   * @param fromCurrency the amount's current currency
   * @param toCurrency   the currency to convert into
   * @return the converted amount rounded to {@value #DISPLAY_SCALE} decimal places, or empty if
   *     a required rate is unavailable
   */
  default Optional<BigDecimal> convert(BigDecimal amount, String fromCurrency, String toCurrency) {
    if (fromCurrency.equals(toCurrency)) {
      return Optional.of(amount.setScale(DISPLAY_SCALE, RoundingMode.HALF_UP));
    }
    if (BYN.equals(fromCurrency)) {
      return getRate(toCurrency, BYN).map(rate -> amount.divide(rate, DISPLAY_SCALE, RoundingMode.HALF_UP));
    }
    if (BYN.equals(toCurrency)) {
      return getRate(fromCurrency, BYN).map(rate -> amount.multiply(rate).setScale(DISPLAY_SCALE, RoundingMode.HALF_UP));
    }
    return getRate(fromCurrency, BYN).flatMap(fromRate -> getRate(toCurrency, BYN)
        .map(toRate -> amount.multiply(fromRate).divide(toRate, DISPLAY_SCALE, RoundingMode.HALF_UP)));
  }
}
