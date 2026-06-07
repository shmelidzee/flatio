package com.flatio.repository;

import com.flatio.domain.country.Country;
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
class CountryRepositoryIT {

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
      .withDatabaseName("flatio_test")
      .withUsername("flatio")
      .withPassword("flatio_test");

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired
  private CountryRepository countryRepository;

  @Test
  void should_find_country_by_code_when_seed_data_loaded() {
    // Given — V3 migration seeds BY

    // When
    var result = countryRepository.findByCode("BY");

    // Then
    assertThat(result).isPresent();
    assertThat(result.get().getName()).isEqualTo("Belarus");
  }

  @Test
  void should_return_empty_when_country_code_not_found() {
    // Given
    var nonExistentCode = "XX";

    // When
    var result = countryRepository.findByCode(nonExistentCode);

    // Then
    assertThat(result).isEmpty();
  }

  @Test
  void should_persist_and_find_new_country() {
    // Given
    var ukraine = new Country();
    ukraine.setCode("UA");
    ukraine.setName("Ukraine");
    countryRepository.save(ukraine);

    // When
    var result = countryRepository.findByCode("UA");

    // Then
    assertThat(result).isPresent();
    assertThat(result.get().getId()).isNotNull();
    assertThat(result.get().getName()).isEqualTo("Ukraine");
  }
}
