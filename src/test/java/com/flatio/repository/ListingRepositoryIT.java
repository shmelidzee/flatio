package com.flatio.repository;

import com.flatio.domain.listing.DealType;
import com.flatio.domain.listing.Listing;
import com.flatio.domain.listing.ListingStatus;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
class ListingRepositoryIT {

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
  private ListingRepository listingRepository;

  @Autowired
  private CountryRepository countryRepository;

  @Autowired
  private CurrencyRepository currencyRepository;

  @Autowired
  private SourceRepository sourceRepository;

  @BeforeEach
  void setUp() {
    listingRepository.deleteAll();
  }

  // -------------------------------------------------------------------------
  // Helper
  // -------------------------------------------------------------------------

  private Listing buildListing(String externalId, ListingStatus status) {
    var source = sourceRepository.findByCode("ONLINER").orElseThrow();
    var currency = currencyRepository.findByCode("BYN").orElseThrow();
    var country = countryRepository.findByCode("BY").orElseThrow();

    var listing = new Listing();
    listing.setExternalId(externalId);
    listing.setSource(source);
    listing.setTitle("Test listing " + externalId);
    listing.setDealType(DealType.RENT);
    listing.setPrice(BigDecimal.valueOf(500));
    listing.setCurrency(currency);
    listing.setCountry(country);
    listing.setStatus(status);
    listing.setSourceUrl("https://onliner.by/listings/" + externalId);
    return listing;
  }

  // -------------------------------------------------------------------------
  // findByExternalIdAndSourceId
  // -------------------------------------------------------------------------

  @Test
  void should_find_listing_by_external_id_and_source_id() {
    // Given
    var saved = listingRepository.save(buildListing("ext-001", ListingStatus.ACTIVE));
    var sourceId = saved.getSource().getId();

    // When
    var result = listingRepository.findByExternalIdAndSourceId("ext-001", sourceId);

    // Then
    assertThat(result).isPresent();
    assertThat(result.get().getExternalId()).isEqualTo("ext-001");
    assertThat(result.get().getId()).isEqualTo(saved.getId());
  }

  @Test
  void should_return_empty_when_external_id_not_found() {
    // Given — no listing with this externalId persisted
    var sourceId = sourceRepository.findByCode("ONLINER").orElseThrow().getId();

    // When
    var result = listingRepository.findByExternalIdAndSourceId("non-existent-id", sourceId);

    // Then
    assertThat(result).isEmpty();
  }

  @Test
  void should_not_persist_duplicate_listing() {
    // Given — first listing saved successfully
    listingRepository.save(buildListing("ext-dup-001", ListingStatus.ACTIVE));
    listingRepository.flush();

    // When / Then — second listing with same (externalId, sourceId) violates UNIQUE constraint
    var duplicate = buildListing("ext-dup-001", ListingStatus.ACTIVE);
    assertThatThrownBy(() -> {
      listingRepository.saveAndFlush(duplicate);
    }).isInstanceOf(DataIntegrityViolationException.class);
  }

  // -------------------------------------------------------------------------
  // findByCountryCodeAndStatus
  // -------------------------------------------------------------------------

  @Test
  void should_return_active_listings_for_country_code() {
    // Given
    listingRepository.save(buildListing("ext-active-1", ListingStatus.ACTIVE));
    listingRepository.save(buildListing("ext-active-2", ListingStatus.ACTIVE));
    listingRepository.flush();

    // When
    var result = listingRepository.findByCountryCodeAndStatus("BY", ListingStatus.ACTIVE);

    // Then
    assertThat(result).hasSize(2);
    assertThat(result).allMatch(l -> l.getStatus() == ListingStatus.ACTIVE);
  }

  @Test
  void should_return_empty_list_when_no_active_listings_for_country() {
    // Given — no listings persisted for BY

    // When
    var result = listingRepository.findByCountryCodeAndStatus("BY", ListingStatus.ACTIVE);

    // Then
    assertThat(result).isEmpty();
  }

  @Test
  void should_not_return_inactive_listing_in_active_search() {
    // Given
    listingRepository.save(buildListing("ext-active-ok", ListingStatus.ACTIVE));
    listingRepository.save(buildListing("ext-inactive-skip", ListingStatus.INACTIVE));
    listingRepository.flush();

    // When
    var result = listingRepository.findByCountryCodeAndStatus("BY", ListingStatus.ACTIVE);

    // Then — only the ACTIVE listing is returned
    assertThat(result).hasSize(1);
    assertThat(result.get(0).getExternalId()).isEqualTo("ext-active-ok");
  }
}
