package com.flatio.service;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Provides current currency exchange rates.
 *
 * <p>Implementations are expected to cache rates internally to avoid
 * repeated external API calls.
 */
public interface CurrencyRateService {

  /**
   * Returns the current USD → BYN exchange rate (1 USD in BYN).
   *
   * <p>Returns {@link Optional#empty()} if the rate cannot be fetched
   * due to a transient error; callers must handle the absent case gracefully.
   *
   * @return USD/BYN rate, or empty on fetch failure
   */
  Optional<BigDecimal> getUsdToByn();
}
