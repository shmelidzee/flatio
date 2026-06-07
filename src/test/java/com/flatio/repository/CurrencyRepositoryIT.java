package com.flatio.repository;

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
class CurrencyRepositoryIT {

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
  private CurrencyRepository currencyRepository;

  @Test
  void should_find_byn_currency_when_seed_data_loaded() {
    // Given — V3 migration seeds BYN, USD, EUR

    // When
    var result = currencyRepository.findByCode("BYN");

    // Then
    assertThat(result).isPresent();
    assertThat(result.get().getSymbol()).isEqualTo("Br");
  }

  @Test
  void should_find_all_seeded_currencies() {
    // Given — V3 seeds three currencies

    // When
    var all = currencyRepository.findAll();

    // Then
    assertThat(all).hasSize(3);
    assertThat(all).extracting("code").containsExactlyInAnyOrder("BYN", "USD", "EUR");
  }

  @Test
  void should_return_empty_when_currency_code_not_found() {
    // Given
    var unknownCode = "GBP";

    // When
    var result = currencyRepository.findByCode(unknownCode);

    // Then
    assertThat(result).isEmpty();
  }
}
