package com.flatio.repository;

import com.flatio.domain.listing.DealType;
import com.flatio.domain.listing.Listing;
import com.flatio.domain.listing.ListingStatus;
import com.flatio.domain.listing.PriceHistory;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
class PriceHistoryRepositoryIT {

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
  private PriceHistoryRepository priceHistoryRepository;

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
    priceHistoryRepository.deleteAll();
    listingRepository.deleteAll();
  }

  // -------------------------------------------------------------------------
  // Helper
  // -------------------------------------------------------------------------

  private Listing buildAndSaveListing(String externalId) {
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
    listing.setStatus(ListingStatus.ACTIVE);
    listing.setSourceUrl("https://onliner.by/listings/" + externalId);
    return listingRepository.saveAndFlush(listing);
  }

  private PriceHistory buildPriceHistory(Listing listing, BigDecimal price, Instant recordedAt) {
    var currency = currencyRepository.findByCode("BYN").orElseThrow();
    var history = new PriceHistory();
    history.setListing(listing);
    history.setPrice(price);
    history.setCurrency(currency);
    history.setRecordedAt(recordedAt);
    return history;
  }

  // -------------------------------------------------------------------------
  // findByListingOrderByRecordedAtDesc
  // -------------------------------------------------------------------------

  @Test
  void should_return_price_history_ordered_newest_first() {
    // Given
    var listing = buildAndSaveListing("ext-ph-001");
    var now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    var older = now.minus(7, ChronoUnit.DAYS);
    var newest = now.minus(1, ChronoUnit.DAYS);

    priceHistoryRepository.save(buildPriceHistory(listing, BigDecimal.valueOf(400), older));
    priceHistoryRepository.save(buildPriceHistory(listing, BigDecimal.valueOf(500), newest));
    priceHistoryRepository.save(buildPriceHistory(listing, BigDecimal.valueOf(450), now));
    priceHistoryRepository.flush();

    // When
    var result = priceHistoryRepository.findByListingOrderByRecordedAtDesc(listing);

    // Then
    assertThat(result).hasSize(3);
    assertThat(result.get(0).getRecordedAt()).isAfterOrEqualTo(result.get(1).getRecordedAt());
    assertThat(result.get(1).getRecordedAt()).isAfterOrEqualTo(result.get(2).getRecordedAt());
  }

  @Test
  void should_return_empty_list_when_listing_has_no_price_history() {
    // Given
    var listing = buildAndSaveListing("ext-ph-002");

    // When
    var result = priceHistoryRepository.findByListingOrderByRecordedAtDesc(listing);

    // Then
    assertThat(result).isEmpty();
  }

  @Test
  void should_return_only_history_for_requested_listing() {
    // Given
    var listing1 = buildAndSaveListing("ext-ph-003");
    var listing2 = buildAndSaveListing("ext-ph-004");
    var now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    priceHistoryRepository.save(buildPriceHistory(listing1, BigDecimal.valueOf(300), now));
    priceHistoryRepository.save(buildPriceHistory(listing2, BigDecimal.valueOf(700), now));
    priceHistoryRepository.flush();

    // When
    var result = priceHistoryRepository.findByListingOrderByRecordedAtDesc(listing1);

    // Then — only listing1's history is returned
    assertThat(result).hasSize(1);
    assertThat(result.get(0).getPrice()).isEqualByComparingTo(BigDecimal.valueOf(300));
  }

  @Test
  void should_fetch_currency_eagerly_to_avoid_n_plus_1() {
    // Given
    var listing = buildAndSaveListing("ext-ph-005");
    var now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    priceHistoryRepository.saveAndFlush(buildPriceHistory(listing, BigDecimal.valueOf(500), now));

    // When
    var result = priceHistoryRepository.findByListingOrderByRecordedAtDesc(listing);

    // Then — currency is initialized (JOIN FETCH), accessing it must not throw LazyInitializationException
    assertThat(result).hasSize(1);
    assertThat(result.get(0).getCurrency()).isNotNull();
    assertThat(result.get(0).getCurrency().getCode()).isEqualTo("BYN");
  }

  @Test
  void should_persist_price_history_with_correct_recorded_at() {
    // Given
    var listing = buildAndSaveListing("ext-ph-006");
    var expectedRecordedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    // When
    var saved = priceHistoryRepository.saveAndFlush(
        buildPriceHistory(listing, BigDecimal.valueOf(600), expectedRecordedAt)
    );

    // Then
    assertThat(saved.getId()).isNotNull();
    assertThat(saved.getRecordedAt()).isEqualTo(expectedRecordedAt);
    assertThat(saved.getPrice()).isEqualByComparingTo(BigDecimal.valueOf(600));
  }
}
