package com.flatio.service.impl;

import com.flatio.domain.currency.ExchangeRate;
import com.flatio.repository.ExchangeRateRepository;
import com.flatio.service.CurrencyRateService;
import java.math.BigDecimal;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads exchange rates persisted by {@link com.flatio.integration.nbrb.scheduler.ExchangeRateSyncJob},
 * rather than fetching them live on every call.
 *
 * <p>Always returns the most recently recorded rate for a pair, regardless of how old it is —
 * if the daily NBRb sync fails, the last successfully synced rate remains in effect rather than
 * the rate becoming unavailable (issue #415).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ExchangeRateServiceImpl implements CurrencyRateService {

  private final ExchangeRateRepository exchangeRateRepository;

  @Override
  public Optional<BigDecimal> getRate(String baseCurrency, String targetCurrency) {
    return exchangeRateRepository
        .findFirstByBaseCurrencyAndTargetCurrencyOrderByEffectiveDateDesc(baseCurrency, targetCurrency)
        .map(ExchangeRate::getRate);
  }
}
