package com.flatio.repository;

import com.flatio.domain.country.Country;
import com.flatio.domain.source.Source;
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
class SourceRepositoryIT {

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
  private SourceRepository sourceRepository;

  @Autowired
  private CountryRepository countryRepository;

  @Test
  void should_find_onliner_source_when_seed_data_loaded() {
    // Given — V3 migration seeds Onliner for BY

    // When
    var result = sourceRepository.findByCode("ONLINER");

    // Then
    assertThat(result).isPresent();
    assertThat(result.get().getName()).isEqualTo("Onliner");
    assertThat(result.get().isActive()).isTrue();
    assertThat(result.get().getCountry().getCode()).isEqualTo("BY");
  }

  @Test
  void should_return_active_sources_for_country_code() {
    // Given — V3 seeds ONLINER, V28 seeds ONLINER_SALE, V31 seeds REALT, V36 seeds REALT_SALE,
    //         V37 seeds REALT_ROOM, V38 seeds REALT_ROOM_SALE, V39 seeds REALT_HOUSE_SALE,
    //         V42 seeds REALT_HOUSE_RENT

    // When
    var result = sourceRepository.findActiveByCountryCode("BY");

    // Then
    assertThat(result).hasSize(8);
    assertThat(result).extracting(Source::getCode)
        .containsExactlyInAnyOrder(
            "ONLINER", "ONLINER_SALE", "REALT", "REALT_SALE",
            "REALT_ROOM", "REALT_ROOM_SALE", "REALT_HOUSE_SALE", "REALT_HOUSE_RENT");
  }

  @Test
  void should_return_empty_list_when_no_active_sources_for_country() {
    // Given — no sources seeded for UA

    // When
    var result = sourceRepository.findActiveByCountryCode("UA");

    // Then
    assertThat(result).isEmpty();
  }

  @Test
  void should_not_return_inactive_source_in_active_search() {
    // Given — a disabled source for BY
    var by = countryRepository.findByCode("BY").orElseThrow();
    var inactive = new Source();
    inactive.setCode("KUFAR");
    inactive.setName("Kufar");
    inactive.setUrl("https://kufar.by");
    inactive.setActive(false);
    inactive.setCountry(by);
    sourceRepository.save(inactive);

    // When
    var result = sourceRepository.findActiveByCountryCode("BY");

    // Then — 8 active sources, not Kufar (inactive)
    assertThat(result).hasSize(8);
    assertThat(result).extracting(Source::getCode)
        .containsExactlyInAnyOrder(
            "ONLINER", "ONLINER_SALE", "REALT", "REALT_SALE",
            "REALT_ROOM", "REALT_ROOM_SALE", "REALT_HOUSE_SALE", "REALT_HOUSE_RENT");
  }

  @Test
  void should_return_empty_when_source_code_not_found() {
    // Given — KUFAR has not been seeded in any migration
    var nonExistentCode = "KUFAR";

    // When
    var result = sourceRepository.findByCode(nonExistentCode);

    // Then
    assertThat(result).isEmpty();
  }
}
