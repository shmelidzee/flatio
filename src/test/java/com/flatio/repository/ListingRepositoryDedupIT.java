package com.flatio.repository;

import com.flatio.domain.listing.DealType;
import com.flatio.domain.listing.Listing;
import com.flatio.domain.listing.ListingStatus;
import com.flatio.domain.source.Source;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
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
class ListingRepositoryDedupIT {

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
    ensureSourceExists("REALT", "Realt.by", "https://realt.by");
  }

  private void ensureSourceExists(String code, String name, String url) {
    if (sourceRepository.findByCode(code).isEmpty()) {
      var country = countryRepository.findByCode("BY").orElseThrow();
      var source = new Source();
      source.setCode(code);
      source.setName(name);
      source.setUrl(url);
      source.setActive(true);
      source.setCountry(country);
      sourceRepository.saveAndFlush(source);
    }
  }

  // -------------------------------------------------------------------------
  // Helper
  // -------------------------------------------------------------------------

  private Listing buildListing(String externalId, String sourceCode, String dedupHash) {
    var source = sourceRepository.findByCode(sourceCode).orElseThrow();
    var currency = currencyRepository.findByCode("BYN").orElseThrow();
    var country = countryRepository.findByCode("BY").orElseThrow();

    var listing = new Listing();
    listing.setExternalId(externalId);
    listing.setSource(source);
    listing.setTitle("Test " + externalId);
    listing.setDealType(DealType.RENT);
    listing.setPrice(BigDecimal.valueOf(500));
    listing.setCurrency(currency);
    listing.setCountry(country);
    listing.setStatus(ListingStatus.ACTIVE);
    listing.setSourceUrl("https://example.com/" + externalId);
    listing.setDedupHash(dedupHash);
    return listing;
  }

  // -------------------------------------------------------------------------
  // findByDedupHashAndSourceNot
  // -------------------------------------------------------------------------

  @Test
  void should_find_listing_with_same_hash_from_different_source() {
    // Given
    var hash = "abc123hash";
    listingRepository.save(buildListing("ext-onliner-1", "ONLINER", hash));
    listingRepository.save(buildListing("ext-realt-1", "REALT", hash));
    listingRepository.flush();

    var onliner = sourceRepository.findByCode("ONLINER").orElseThrow();

    // When — search for hash, excluding ONLINER source
    var result = listingRepository.findByDedupHashAndSourceNot(hash, onliner);

    // Then — only REALT listing is returned
    assertThat(result).hasSize(1);
    assertThat(result.get(0).getSource().getCode()).isEqualTo("REALT");
  }

  @Test
  void should_return_empty_when_no_other_source_has_same_hash() {
    // Given
    var hash = "uniquehash";
    listingRepository.save(buildListing("ext-onliner-2", "ONLINER", hash));
    listingRepository.flush();

    var onliner = sourceRepository.findByCode("ONLINER").orElseThrow();

    // When — ONLINER is excluded; no other source has this hash
    var result = listingRepository.findByDedupHashAndSourceNot(hash, onliner);

    // Then
    assertThat(result).isEmpty();
  }

  @Test
  void should_return_empty_when_no_listing_has_given_hash() {
    // Given — listing with a different hash
    listingRepository.save(buildListing("ext-onliner-3", "ONLINER", "differenthash"));
    listingRepository.flush();

    var onliner = sourceRepository.findByCode("ONLINER").orElseThrow();

    // When
    var result = listingRepository.findByDedupHashAndSourceNot("notpresent", onliner);

    // Then
    assertThat(result).isEmpty();
  }

  @Test
  void should_not_return_same_source_listing_even_if_hash_matches() {
    // Given — two listings from ONLINER with the same hash (unusual but possible)
    var hash = "sharedhash";
    listingRepository.save(buildListing("ext-onliner-4a", "ONLINER", hash));
    listingRepository.save(buildListing("ext-onliner-4b", "ONLINER", hash));
    listingRepository.flush();

    var onliner = sourceRepository.findByCode("ONLINER").orElseThrow();

    // When — both are from ONLINER which is excluded
    var result = listingRepository.findByDedupHashAndSourceNot(hash, onliner);

    // Then
    assertThat(result).isEmpty();
  }

  @Test
  void should_find_null_hash_listing_from_other_source() {
    // Given — listing with no dedup_hash from ONLINER
    listingRepository.save(buildListing("ext-nohash-1", "ONLINER", null));
    listingRepository.flush();

    var realt = sourceRepository.findByCode("REALT").orElseThrow();

    // When — Hibernate 6 translates null parameter to IS NULL, so the listing IS found
    var result = listingRepository.findByDedupHashAndSourceNot(null, realt);

    // Then — ONLINER listing with null hash is returned (source ONLINER != excluded REALT)
    assertThat(result).hasSize(1);
    assertThat(result.get(0).getSource().getCode()).isEqualTo("ONLINER");
  }
}
