package com.flatio.repository;

import com.flatio.domain.currency.ExchangeRate;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
class ExchangeRateRepositoryIT {

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
      .withDatabaseName("flatio_test");

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired
  private ExchangeRateRepository exchangeRateRepository;

  @Test
  void should_find_most_recent_rate_when_multiple_days_recorded() {
    // Given
    saveRate("USD", "BYN", "3.20", LocalDate.now().minusDays(2));
    saveRate("USD", "BYN", "3.25", LocalDate.now().minusDays(1));
    saveRate("USD", "BYN", "3.30", LocalDate.now());

    // When
    var result = exchangeRateRepository.findFirstByBaseCurrencyAndTargetCurrencyOrderByEffectiveDateDesc("USD", "BYN");

    // Then
    assertThat(result).isPresent();
    assertThat(result.get().getRate()).isEqualByComparingTo("3.30");
  }

  @Test
  void should_return_empty_when_pair_never_recorded() {
    // Given — no rows saved for GBP

    // When
    var result = exchangeRateRepository.findFirstByBaseCurrencyAndTargetCurrencyOrderByEffectiveDateDesc("GBP", "BYN");

    // Then
    assertThat(result).isEmpty();
  }

  @Test
  void should_find_rate_for_exact_day_when_recorded() {
    // Given
    var today = LocalDate.now();
    saveRate("EUR", "BYN", "3.55", today);

    // When
    var result = exchangeRateRepository.findByBaseCurrencyAndTargetCurrencyAndEffectiveDate("EUR", "BYN", today);

    // Then
    assertThat(result).isPresent();
    assertThat(result.get().getRate()).isEqualByComparingTo("3.55");
  }

  @Test
  void should_return_empty_when_no_rate_recorded_for_that_exact_day() {
    // Given
    saveRate("EUR", "BYN", "3.55", LocalDate.now().minusDays(1));

    // When
    var result = exchangeRateRepository.findByBaseCurrencyAndTargetCurrencyAndEffectiveDate(
        "EUR", "BYN", LocalDate.now());

    // Then
    assertThat(result).isEmpty();
  }

  @Test
  void should_enforce_unique_constraint_on_base_target_date() {
    // Given
    var today = LocalDate.now();
    saveRate("USD", "BYN", "3.25", today);
    var duplicate = new ExchangeRate();
    duplicate.setBaseCurrency("USD");
    duplicate.setTargetCurrency("BYN");
    duplicate.setRate(BigDecimal.valueOf(3.30));
    duplicate.setEffectiveDate(today);

    // When / Then
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> {
      exchangeRateRepository.saveAndFlush(duplicate);
    }).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  }

  private void saveRate(String base, String target, String rate, LocalDate date) {
    var exchangeRate = new ExchangeRate();
    exchangeRate.setBaseCurrency(base);
    exchangeRate.setTargetCurrency(target);
    exchangeRate.setRate(new BigDecimal(rate));
    exchangeRate.setEffectiveDate(date);
    exchangeRateRepository.saveAndFlush(exchangeRate);
  }
}
