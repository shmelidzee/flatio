package com.flatio.service.impl;

import com.flatio.domain.currency.ExchangeRate;
import com.flatio.repository.ExchangeRateRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExchangeRateServiceImplTest {

  @Mock
  private ExchangeRateRepository exchangeRateRepository;

  @InjectMocks
  private ExchangeRateServiceImpl exchangeRateService;

  @Test
  void should_return_rate_when_pair_recorded() {
    // Given
    var exchangeRate = new ExchangeRate();
    exchangeRate.setBaseCurrency("USD");
    exchangeRate.setTargetCurrency("BYN");
    exchangeRate.setRate(new BigDecimal("3.25"));
    exchangeRate.setEffectiveDate(LocalDate.now());
    when(exchangeRateRepository.findFirstByBaseCurrencyAndTargetCurrencyOrderByEffectiveDateDesc("USD", "BYN"))
        .thenReturn(Optional.of(exchangeRate));

    // When
    Optional<BigDecimal> result = exchangeRateService.getRate("USD", "BYN");

    // Then
    assertThat(result).isPresent();
    assertThat(result.get()).isEqualByComparingTo("3.25");
  }

  @Test
  void should_return_empty_when_pair_never_recorded() {
    // Given
    when(exchangeRateRepository.findFirstByBaseCurrencyAndTargetCurrencyOrderByEffectiveDateDesc("GBP", "BYN"))
        .thenReturn(Optional.empty());

    // When
    Optional<BigDecimal> result = exchangeRateService.getRate("GBP", "BYN");

    // Then
    assertThat(result).isEmpty();
  }
}
