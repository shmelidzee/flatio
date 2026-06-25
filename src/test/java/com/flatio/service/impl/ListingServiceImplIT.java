package com.flatio.service.impl;

import com.flatio.domain.listing.DealType;
import com.flatio.domain.listing.Listing;
import com.flatio.domain.listing.ListingStatus;
import com.flatio.repository.CountryRepository;
import com.flatio.repository.CurrencyRepository;
import com.flatio.repository.ListingRepository;
import com.flatio.repository.PriceHistoryRepository;
import com.flatio.repository.SourceRepository;
import com.flatio.web.dto.ListingSearchCriteria;
import com.flatio.web.dto.ListingSummaryResponse;
import com.flatio.web.mapper.ListingMapper;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies {@link ListingServiceImpl#search} against a real Hibernate {@code Specification}
 * execution (Testcontainers PostgreSQL), not a mocked repository — a mocked
 * {@code Specification} never resolves JPA attribute paths and would silently miss the
 * {@code cityRef} regression fixed in #213.
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

  @Test
  void should_not_throw_when_searching_with_city_id_set() {
    // Given — a legacy saved search may still carry a cityId persisted before #213 disabled
    // the city-selection wizard step; the search must no longer crash on it
    var listingMapper = mock(ListingMapper.class);
    when(listingMapper.toSummaryResponse(org.mockito.ArgumentMatchers.any()))
        .thenReturn(mock(ListingSummaryResponse.class));
    var listingService = new ListingServiceImpl(listingRepository, priceHistoryRepository, listingMapper);

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
    listingRepository.save(listing);
    listingRepository.flush();

    var criteria = new ListingSearchCriteria(
        null, null, null, null, 42L, null, null, null, null, null, null
    );

    // When / Then — real Hibernate criteria resolution must not fail on a non-existent "cityRef"
    assertThatNoException().isThrownBy(() -> listingService.search(criteria, PageRequest.of(0, 10)));
  }
}
