package com.flatio.integration.nbrb.scheduler;

import com.flatio.domain.currency.ExchangeRate;
import com.flatio.integration.nbrb.client.NbrbClient;
import com.flatio.integration.nbrb.config.NbrbProperties;
import com.flatio.repository.ExchangeRateRepository;
import com.flatio.service.CurrencyRateService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Syncs each configured currency's official rate against BYN from NBRb into {@link ExchangeRate}.
 *
 * <p>A failure fetching or persisting one currency's rate is isolated from the others — it does
 * not abort the run — and never removes or overwrites a previously recorded rate: {@link
 * com.flatio.service.impl.ExchangeRateServiceImpl} simply keeps serving the last successfully
 * synced value until the next successful run (issue #415).
 *
 * <p>Cron schedule is configurable via {@code flatio.nbrb.sync-cron}
 * (env: {@code FLATIO_NBRB_SYNC_CRON}); default is once daily at 05:00, after NBRb typically
 * publishes the day's official rates. Also runs once immediately on application startup so a
 * freshly deployed instance is not left without any rate until the first scheduled run.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ExchangeRateSyncJob {

  private static final String TARGET_CURRENCY = CurrencyRateService.BYN;

  private final NbrbClient nbrbClient;
  private final ExchangeRateRepository exchangeRateRepository;
  private final NbrbProperties properties;

  /**
   * Triggers an immediate sync on startup, so a freshly deployed instance has a rate available
   * without waiting for the first scheduled run.
   */
  @Async("startupSyncExecutor")
  @EventListener(ApplicationReadyEvent.class)
  public void onApplicationReady() {
    log.info("NBRb exchange rate sync triggered on startup");
    syncAll();
  }

  /**
   * Fetches and persists today's rate for every currency configured in
   * {@link NbrbProperties#currencyIds()}.
   */
  @Scheduled(cron = "${flatio.nbrb.sync-cron:0 0 5 * * *}")
  public void syncAll() {
    for (Map.Entry<String, Integer> entry : properties.currencyIds().entrySet()) {
      syncOne(entry.getKey(), entry.getValue());
    }
  }

  private void syncOne(String currencyCode, int nbrbCurrencyId) {
    try {
      nbrbClient.fetchRate(nbrbCurrencyId).ifPresentOrElse(
          rate -> persist(currencyCode, rate),
          () -> log.warn("NBRb rate unavailable, keeping last known rate in effect: currency={}", currencyCode)
      );
    } catch (Exception e) {
      log.warn("NBRb sync failed for currency, keeping last known rate in effect: currency={}, error={}",
          currencyCode, e.getMessage());
    }
  }

  @Transactional
  void persist(String currencyCode, BigDecimal rate) {
    LocalDate today = LocalDate.now();
    ExchangeRate exchangeRate = exchangeRateRepository
        .findByBaseCurrencyAndTargetCurrencyAndEffectiveDate(currencyCode, TARGET_CURRENCY, today)
        .orElseGet(ExchangeRate::new);
    exchangeRate.setBaseCurrency(currencyCode);
    exchangeRate.setTargetCurrency(TARGET_CURRENCY);
    exchangeRate.setRate(rate);
    exchangeRate.setEffectiveDate(today);
    exchangeRateRepository.save(exchangeRate);
    log.info("NBRb rate synced: base={}, target={}, rate={}, date={}", currencyCode, TARGET_CURRENCY, rate, today);
  }
}
