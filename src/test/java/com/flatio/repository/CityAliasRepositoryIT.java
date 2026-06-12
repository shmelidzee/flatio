package com.flatio.repository;

import com.flatio.domain.city.City;
import com.flatio.domain.city.CityAlias;
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
class CityAliasRepositoryIT {

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
  private CityAliasRepository cityAliasRepository;

  @Autowired
  private CityRepository cityRepository;

  @Test
  void should_find_minsk_alias_for_realt_source() {
    // Given — V24 seeds alias «Мінск» / source «realt» for Минск

    // When
    var result = cityAliasRepository.findByAliasAndSourceId("Мінск", "realt");

    // Then
    assertThat(result).isPresent();
    assertThat(result.get().getCity().getNameRu()).isEqualTo("Минск");
    assertThat(result.get().getSourceId()).isEqualTo("realt");
  }

  @Test
  void should_find_minsk_alias_for_onliner_source() {
    // Given — V24 seeds alias «Минск» / source «onliner»

    // When
    var result = cityAliasRepository.findByAliasAndSourceId("Минск", "onliner");

    // Then
    assertThat(result).isPresent();
    assertThat(result.get().getCity().getNameRu()).isEqualTo("Минск");
  }

  @Test
  void should_return_empty_when_alias_not_found_for_source() {
    // Given
    var unknownAlias = "Варшава";

    // When
    var result = cityAliasRepository.findByAliasAndSourceId(unknownAlias, "realt");

    // Then
    assertThat(result).isEmpty();
  }

  @Test
  void should_return_empty_when_alias_exists_but_source_differs() {
    // Given — alias «Минск» exists for «realt» but not for «unknown_source»

    // When
    var result = cityAliasRepository.findByAliasAndSourceId("Минск", "unknown_source");

    // Then
    assertThat(result).isEmpty();
  }

  @Test
  void should_persist_and_find_new_alias() {
    // Given
    var minsk = cityRepository.findByNameRu("Минск").orElseThrow();
    var alias = new CityAlias();
    alias.setCity(minsk);
    alias.setAlias("Мiнск");
    alias.setSourceId("test_source");
    cityAliasRepository.save(alias);

    // When
    var result = cityAliasRepository.findByAliasAndSourceId("Мiнск", "test_source");

    // Then
    assertThat(result).isPresent();
    assertThat(result.get().getId()).isNotNull();
    assertThat(result.get().getCity().getNameRu()).isEqualTo("Минск");
  }

  @Test
  void should_find_brest_belarusian_alias_for_kufar_source() {
    // Given — V24 seeds alias «Брэст» for kufar

    // When
    var result = cityAliasRepository.findByAliasAndSourceId("Брэст", "kufar");

    // Then
    assertThat(result).isPresent();
    assertThat(result.get().getCity().getNameRu()).isEqualTo("Брест");
  }
}
