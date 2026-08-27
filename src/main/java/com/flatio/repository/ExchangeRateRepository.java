package com.flatio.repository;

import com.flatio.domain.currency.ExchangeRate;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExchangeRateRepository extends JpaRepository<ExchangeRate, Long> {

  /**
   * Finds the most recent rate for the given currency pair, regardless of how long ago it was
   * recorded.
   *
   * <p>Used as the "last known rate" fallback (issue #415): if today's NBRb sync failed, the
   * most recent successful day's rate is still returned rather than nothing at all.
   *
   * @param baseCurrency   the currency being converted from
   * @param targetCurrency the currency being converted to
   * @return the latest rate for this pair, or empty if none has ever been recorded
   */
  Optional<ExchangeRate> findFirstByBaseCurrencyAndTargetCurrencyOrderByEffectiveDateDesc(
      String baseCurrency, String targetCurrency);

  /**
   * Finds the rate recorded for the given currency pair on a specific day.
   *
   * <p>Used by the sync job to decide whether today's rate already exists (update) or needs to
   * be inserted.
   *
   * @param baseCurrency   the currency being converted from
   * @param targetCurrency the currency being converted to
   * @param effectiveDate  the day the rate was published for
   * @return the rate for that exact day, or empty if not yet recorded
   */
  Optional<ExchangeRate> findByBaseCurrencyAndTargetCurrencyAndEffectiveDate(
      String baseCurrency, String targetCurrency, LocalDate effectiveDate);
}
