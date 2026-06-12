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
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
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
    return buildListing(externalId, status, DealType.RENT);
  }

  private Listing buildListing(String externalId, ListingStatus status, DealType dealType) {
    var source = sourceRepository.findByCode("ONLINER").orElseThrow();
    var currency = currencyRepository.findByCode("BYN").orElseThrow();
    var country = countryRepository.findByCode("BY").orElseThrow();

    var listing = new Listing();
    listing.setExternalId(externalId);
    listing.setSource(source);
    listing.setTitle("Test listing " + externalId);
    listing.setDealType(dealType);
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

  // -------------------------------------------------------------------------
  // findPageByCountryCodeAndStatus (paginated)
  // -------------------------------------------------------------------------

  @Test
  void should_return_first_page_of_listings() {
    // Given — 5 active listings
    for (int i = 1; i <= 5; i++) {
      listingRepository.save(buildListing("ext-paged-" + i, ListingStatus.ACTIVE));
    }
    listingRepository.flush();

    // When — request first page of 3
    var page = listingRepository.findPageByCountryCodeAndStatus("BY", ListingStatus.ACTIVE, PageRequest.of(0, 3));

    // Then
    assertThat(page.getTotalElements()).isEqualTo(5);
    assertThat(page.getTotalPages()).isEqualTo(2);
    assertThat(page.getContent()).hasSize(3);
    assertThat(page.getNumber()).isEqualTo(0);
  }

  @Test
  void should_return_second_page_with_remaining_listings() {
    // Given — 5 active listings
    for (int i = 1; i <= 5; i++) {
      listingRepository.save(buildListing("ext-page2-" + i, ListingStatus.ACTIVE));
    }
    listingRepository.flush();

    // When — request second page of 3
    var page = listingRepository.findPageByCountryCodeAndStatus("BY", ListingStatus.ACTIVE, PageRequest.of(1, 3));

    // Then — only 2 remaining listings on the second page
    assertThat(page.getTotalElements()).isEqualTo(5);
    assertThat(page.getContent()).hasSize(2);
    assertThat(page.isLast()).isTrue();
  }

  @Test
  void should_exclude_inactive_listings_from_paginated_results() {
    // Given — mix of active and inactive listings
    listingRepository.save(buildListing("ext-pg-active-1", ListingStatus.ACTIVE));
    listingRepository.save(buildListing("ext-pg-active-2", ListingStatus.ACTIVE));
    listingRepository.save(buildListing("ext-pg-inactive-1", ListingStatus.INACTIVE));
    listingRepository.flush();

    // When
    var page = listingRepository.findPageByCountryCodeAndStatus("BY", ListingStatus.ACTIVE, PageRequest.of(0, 10));

    // Then — only active listings in result
    assertThat(page.getTotalElements()).isEqualTo(2);
    assertThat(page.getContent()).allMatch(l -> l.getStatus() == ListingStatus.ACTIVE);
  }

  @Test
  void should_return_empty_page_when_no_listings_exist() {
    // Given — no listings persisted

    // When
    var page = listingRepository.findPageByCountryCodeAndStatus("BY", ListingStatus.ACTIVE, PageRequest.of(0, 10));

    // Then
    assertThat(page.getTotalElements()).isEqualTo(0);
    assertThat(page.getContent()).isEmpty();
  }

  // -------------------------------------------------------------------------
  // JPA Specification — dealType RENT_DAILY filter (#92)
  // -------------------------------------------------------------------------

  @Test
  void should_return_only_rent_daily_listings_when_filtered_by_deal_type() {
    // Given — mix of RENT_DAILY, RENT and SELL listings
    listingRepository.save(buildListing("ext-rd-1", ListingStatus.ACTIVE, DealType.RENT_DAILY));
    listingRepository.save(buildListing("ext-rd-2", ListingStatus.ACTIVE, DealType.RENT_DAILY));
    listingRepository.save(buildListing("ext-rent-1", ListingStatus.ACTIVE, DealType.RENT));
    listingRepository.save(buildListing("ext-sell-1", ListingStatus.ACTIVE, DealType.SELL));
    listingRepository.flush();

    // When
    Specification<Listing> spec = (root, query, cb) -> cb.equal(root.get("dealType"), DealType.RENT_DAILY);
    var result = listingRepository.findAll(spec);

    // Then — only RENT_DAILY listings returned
    assertThat(result).hasSize(2);
    assertThat(result).allMatch(l -> l.getDealType() == DealType.RENT_DAILY);
  }

  @Test
  void should_return_empty_list_when_no_rent_daily_listings_exist() {
    // Given — only RENT listings persisted
    listingRepository.save(buildListing("ext-only-rent-1", ListingStatus.ACTIVE, DealType.RENT));
    listingRepository.save(buildListing("ext-only-rent-2", ListingStatus.ACTIVE, DealType.RENT));
    listingRepository.flush();

    // When
    Specification<Listing> spec = (root, query, cb) -> cb.equal(root.get("dealType"), DealType.RENT_DAILY);
    var result = listingRepository.findAll(spec);

    // Then
    assertThat(result).isEmpty();
  }
}
