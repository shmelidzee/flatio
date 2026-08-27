package com.flatio.integration.nbrb.scheduler;

import com.flatio.domain.currency.ExchangeRate;
import com.flatio.integration.nbrb.client.NbrbClient;
import com.flatio.integration.nbrb.config.NbrbProperties;
import com.flatio.repository.ExchangeRateRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExchangeRateSyncJobTest {

  @Mock
  private NbrbClient nbrbClient;

  @Mock
  private ExchangeRateRepository exchangeRateRepository;

  @Mock
  private NbrbProperties properties;

  @InjectMocks
  private ExchangeRateSyncJob exchangeRateSyncJob;

  @Test
  void should_insert_new_rate_when_no_entry_exists_for_today() {
    // Given
    when(properties.currencyIds()).thenReturn(Map.of("USD", 431));
    when(nbrbClient.fetchRate(431)).thenReturn(Optional.of(new BigDecimal("3.25")));
    when(exchangeRateRepository.findByBaseCurrencyAndTargetCurrencyAndEffectiveDate(
        eq("USD"), eq("BYN"), any(LocalDate.class))).thenReturn(Optional.empty());

    // When
    exchangeRateSyncJob.syncAll();

    // Then
    ArgumentCaptor<ExchangeRate> captor = ArgumentCaptor.forClass(ExchangeRate.class);
    verify(exchangeRateRepository).save(captor.capture());
    assertThat(captor.getValue().getBaseCurrency()).isEqualTo("USD");
    assertThat(captor.getValue().getTargetCurrency()).isEqualTo("BYN");
    assertThat(captor.getValue().getRate()).isEqualByComparingTo("3.25");
    assertThat(captor.getValue().getEffectiveDate()).isEqualTo(LocalDate.now());
  }

  @Test
  void should_update_existing_rate_when_entry_already_exists_for_today() {
    // Given
    var existing = new ExchangeRate();
    existing.setId(1L);
    existing.setBaseCurrency("USD");
    existing.setTargetCurrency("BYN");
    existing.setRate(new BigDecimal("3.10"));
    existing.setEffectiveDate(LocalDate.now());
    when(properties.currencyIds()).thenReturn(Map.of("USD", 431));
    when(nbrbClient.fetchRate(431)).thenReturn(Optional.of(new BigDecimal("3.30")));
    when(exchangeRateRepository.findByBaseCurrencyAndTargetCurrencyAndEffectiveDate(
        eq("USD"), eq("BYN"), any(LocalDate.class))).thenReturn(Optional.of(existing));

    // When
    exchangeRateSyncJob.syncAll();

    // Then
    ArgumentCaptor<ExchangeRate> captor = ArgumentCaptor.forClass(ExchangeRate.class);
    verify(exchangeRateRepository).save(captor.capture());
    assertThat(captor.getValue().getId()).isEqualTo(1L);
    assertThat(captor.getValue().getRate()).isEqualByComparingTo("3.30");
  }

  @Test
  void should_keep_last_known_rate_when_nbrb_returns_empty() {
    // Given
    when(properties.currencyIds()).thenReturn(Map.of("USD", 431));
    when(nbrbClient.fetchRate(431)).thenReturn(Optional.empty());

    // When
    exchangeRateSyncJob.syncAll();

    // Then
    verify(exchangeRateRepository, never()).save(any());
  }

  @Test
  void should_isolate_failure_when_one_currency_throws_exception_during_sync() {
    // Given — two currencies, USD's fetch throws, EUR must still be synced
    Map<String, Integer> currencyIds = new LinkedHashMap<>();
    currencyIds.put("USD", 431);
    currencyIds.put("EUR", 451);
    when(properties.currencyIds()).thenReturn(currencyIds);
    when(nbrbClient.fetchRate(431)).thenThrow(new RuntimeException("nbrb unavailable"));
    when(nbrbClient.fetchRate(451)).thenReturn(Optional.of(new BigDecimal("3.55")));
    when(exchangeRateRepository.findByBaseCurrencyAndTargetCurrencyAndEffectiveDate(
        eq("EUR"), eq("BYN"), any(LocalDate.class))).thenReturn(Optional.empty());

    // When / Then — does not propagate the exception
    exchangeRateSyncJob.syncAll();

    ArgumentCaptor<ExchangeRate> captor = ArgumentCaptor.forClass(ExchangeRate.class);
    verify(exchangeRateRepository).save(captor.capture());
    assertThat(captor.getValue().getBaseCurrency()).isEqualTo("EUR");
  }

  @Test
  void should_trigger_sync_all_when_application_ready_event_fires() {
    // Given
    when(properties.currencyIds()).thenReturn(Map.of("USD", 431));
    when(nbrbClient.fetchRate(431)).thenReturn(Optional.of(new BigDecimal("3.25")));
    when(exchangeRateRepository.findByBaseCurrencyAndTargetCurrencyAndEffectiveDate(
        eq("USD"), eq("BYN"), any(LocalDate.class))).thenReturn(Optional.empty());

    // When
    exchangeRateSyncJob.onApplicationReady();

    // Then
    verify(exchangeRateRepository).save(any(ExchangeRate.class));
  }
}
