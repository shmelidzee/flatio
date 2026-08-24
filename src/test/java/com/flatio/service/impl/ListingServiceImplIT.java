package com.flatio.service.impl;

import com.flatio.domain.listing.DealType;
import com.flatio.domain.listing.Listing;
import com.flatio.domain.listing.ListingStatus;
import com.flatio.repository.CountryRepository;
import com.flatio.repository.CurrencyRepository;
import com.flatio.repository.ListingRepository;
import com.flatio.repository.PriceHistoryRepository;
import com.flatio.repository.SourceRepository;
import com.flatio.service.CurrencyRateService;
import com.flatio.web.dto.ListingSearchCriteria;
import com.flatio.web.dto.ListingSummaryResponse;
import com.flatio.web.mapper.ListingMapper;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies {@link ListingServiceImpl#search} against a real Hibernate {@code Specification}
 * execution (Testcontainers PostgreSQL), not a mocked repository — a mocked
 * {@code Specification} never resolves JPA attribute paths and would silently miss regressions.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
class ListingServiceImplIT {

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
  private PriceHistoryRepository priceHistoryRepository;

  @Autowired
  private CountryRepository countryRepository;

  @Autowired
  private CurrencyRepository currencyRepository;

  @Autowired
  private SourceRepository sourceRepository;

  private ListingServiceImpl listingService;
  private ListingMapper listingMapper;

  @BeforeEach
  void setUp() {
    listingMapper = mock(ListingMapper.class);
    when(listingMapper.toSummaryResponse(ArgumentMatchers.any()))
        .thenReturn(mock(ListingSummaryResponse.class));
    var currencyRateService = mock(CurrencyRateService.class);
    when(currencyRateService.getUsdToByn()).thenReturn(Optional.empty());
    listingService = new ListingServiceImpl(
        listingRepository, priceHistoryRepository, sourceRepository, currencyRepository, listingMapper, currencyRateService);
  }

  // -------------------------------------------------------------------------
  // buildSearchSpec — price filter bypass for USD listings without BYN conversion (#264)
  // -------------------------------------------------------------------------

  @Test
  void should_include_usd_listing_without_byn_conversion_when_price_filter_is_set() {
    // Given — Realt listing priced in USD; NBRB rate was unavailable at ingestion so priceByn=null.
    // Previously, COALESCE(null, 250) = 250 was compared against BYN range [500..2000]
    // and incorrectly excluded the listing.
    var source = sourceRepository.findByCode("REALT").orElseThrow();
    var usdCurrency = currencyRepository.findByCode("USD").orElseThrow();
    var country = countryRepository.findByCode("BY").orElseThrow();

    var listing = new Listing();
    listing.setExternalId("ext-usd-no-byn-264");
    listing.setSource(source);
    listing.setTitle("USD listing — no BYN conversion");
    listing.setDealType(DealType.RENT);
    listing.setPrice(BigDecimal.valueOf(250));
    listing.setCurrency(usdCurrency);
    listing.setPriceByn(null);
    listing.setCountry(country);
    listing.setStatus(ListingStatus.ACTIVE);
    listing.setSourceUrl("https://realt.by/ext-usd-no-byn-264");
    listingRepository.saveAndFlush(listing);

    var criteria = new ListingSearchCriteria(
        null, null, null, null, null,
        BigDecimal.valueOf(500), BigDecimal.valueOf(2_000),
        null, null, null, null
    );

    // When
    var result = listingService.search(criteria, PageRequest.of(0, 10));

    // Then — USD listing without BYN conversion bypasses the BYN price filter
    assertThat(result.getTotalElements()).isEqualTo(1);
  }

  @Test
  void should_exclude_byn_listing_below_price_min_when_price_filter_is_set() {
    // Given — Onliner listing priced in BYN below the requested minimum
    var source = sourceRepository.findByCode("ONLINER").orElseThrow();
    var bynCurrency = currencyRepository.findByCode("BYN").orElseThrow();
    var country = countryRepository.findByCode("BY").orElseThrow();

    var listing = new Listing();
    listing.setExternalId("ext-byn-below-min-264");
    listing.setSource(source);
    listing.setTitle("BYN listing below minimum");
    listing.setDealType(DealType.RENT);
    listing.setPrice(BigDecimal.valueOf(300));
    listing.setCurrency(bynCurrency);
    listing.setCountry(country);
    listing.setStatus(ListingStatus.ACTIVE);
    listing.setSourceUrl("https://onliner.by/ext-byn-below-min-264");
    listingRepository.saveAndFlush(listing);

    var criteria = new ListingSearchCriteria(
        null, null, null, null, null,
        BigDecimal.valueOf(500), null,
        null, null, null, null
    );

    // When
    var result = listingService.search(criteria, PageRequest.of(0, 10));

    // Then — BYN listing at 300 is correctly excluded (300 < priceMin 500)
    assertThat(result.getTotalElements()).isZero();
  }

  @Test
  void should_include_byn_listing_within_price_range_when_filter_is_set() {
    // Given — Onliner listing priced in BYN within the requested range
    var source = sourceRepository.findByCode("ONLINER").orElseThrow();
    var bynCurrency = currencyRepository.findByCode("BYN").orElseThrow();
    var country = countryRepository.findByCode("BY").orElseThrow();

    var listing = new Listing();
    listing.setExternalId("ext-byn-in-range-264");
    listing.setSource(source);
    listing.setTitle("BYN listing in price range");
    listing.setDealType(DealType.RENT);
    listing.setPrice(BigDecimal.valueOf(800));
    listing.setCurrency(bynCurrency);
    listing.setCountry(country);
    listing.setStatus(ListingStatus.ACTIVE);
    listing.setSourceUrl("https://onliner.by/ext-byn-in-range-264");
    listingRepository.saveAndFlush(listing);

    var criteria = new ListingSearchCriteria(
        null, null, null, null, null,
        BigDecimal.valueOf(500), BigDecimal.valueOf(1_000),
        null, null, null, null
    );

    // When
    var result = listingService.search(criteria, PageRequest.of(0, 10));

    // Then — BYN listing at 800 is correctly included (500 ≤ 800 ≤ 1000)
    assertThat(result.getTotalElements()).isEqualTo(1);
  }

  // -------------------------------------------------------------------------
  // buildSearchSpec — cityRef regression (#213)
  // -------------------------------------------------------------------------

  @Test
  void should_not_throw_when_searching_with_city_id_set() {
    // Given — a legacy saved search may still carry a cityId persisted before #213 disabled
    // the city-selection wizard step; the search must no longer crash on it
    var source = sourceRepository.findByCode("ONLINER").orElseThrow();
    var currency = currencyRepository.findByCode("BYN").orElseThrow();
    var country = countryRepository.findByCode("BY").orElseThrow();

    var listing = new Listing();
    listing.setExternalId("ext-cityid-it-1");
    listing.setSource(source);
    listing.setTitle("Test listing");
    listing.setDealType(DealType.RENT);
    listing.setPrice(BigDecimal.valueOf(500));
    listing.setCurrency(currency);
    listing.setCountry(country);
    listing.setStatus(ListingStatus.ACTIVE);
    listing.setSourceUrl("https://onliner.by/listings/ext-cityid-it-1");
    listingRepository.saveAndFlush(listing);

    var criteria = new ListingSearchCriteria(
        null, null, null, null, 42L, null, null, null, null, null, null
    );

    // When / Then — real Hibernate criteria resolution must not fail on a non-existent "cityRef"
    assertThatNoException().isThrownBy(() -> listingService.search(criteria, PageRequest.of(0, 10)));
  }
}
