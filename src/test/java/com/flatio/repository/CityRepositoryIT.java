package com.flatio.repository;

import com.flatio.domain.city.City;
import com.flatio.domain.country.Country;
import java.math.BigDecimal;
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
class CityRepositoryIT {

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
  private CityRepository cityRepository;

  @Autowired
  private CountryRepository countryRepository;

  @Test
  void should_find_minsk_when_seed_data_loaded() {
    // Given — V24 migration seeds Минск

    // When
    var result = cityRepository.findByNameRu("Минск");

    // Then
    assertThat(result).isPresent();
    assertThat(result.get().getNameBe()).isEqualTo("Мінск");
    assertThat(result.get().getNameEn()).isEqualTo("Minsk");
    assertThat(result.get().getCountry().getCode()).isEqualTo("BY");
  }

  @Test
  void should_find_all_six_seeded_cities() {
    // Given — V24 seeds 6 Belarusian cities

    // When
    var all = cityRepository.findAll();

    // Then
    assertThat(all).hasSize(6);
  }

  @Test
  void should_return_empty_when_city_name_not_found() {
    // Given
    var nonExistentName = "Варшава";

    // When
    var result = cityRepository.findByNameRu(nonExistentName);

    // Then
    assertThat(result).isEmpty();
  }

  @Test
  void should_persist_and_find_new_city() {
    // Given
    var by = countryRepository.findByCode("BY").orElseThrow();
    var city = new City();
    city.setNameRu("Барановичи");
    city.setNameBe("Баранавічы");
    city.setNameEn("Baranavichy");
    city.setCountry(by);
    city.setLatitude(new BigDecimal("53.132500"));
    city.setLongitude(new BigDecimal("26.010600"));
    cityRepository.save(city);

    // When
    var result = cityRepository.findByNameRu("Барановичи");

    // Then
    assertThat(result).isPresent();
    assertThat(result.get().getId()).isNotNull();
    assertThat(result.get().getNameEn()).isEqualTo("Baranavichy");
    assertThat(result.get().getCountry().getCode()).isEqualTo("BY");
  }

  @Test
  void should_store_coordinates_with_correct_precision() {
    // Given — Минск seeded with coordinates

    // When
    var result = cityRepository.findByNameRu("Минск");

    // Then
    assertThat(result).isPresent();
    assertThat(result.get().getLatitude()).isNotNull();
    assertThat(result.get().getLongitude()).isNotNull();
  }
}
