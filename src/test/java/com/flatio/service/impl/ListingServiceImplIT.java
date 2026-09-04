package com.flatio.service.impl;

import com.flatio.domain.blacklist.BlacklistEntry;
import com.flatio.domain.blacklist.BlacklistEntryType;
import com.flatio.domain.listing.DealType;
import com.flatio.domain.listing.Listing;
import com.flatio.domain.listing.ListingStatus;
import com.flatio.domain.user.User;
import com.flatio.repository.BlacklistEntryRepository;
import com.flatio.repository.CountryRepository;
import com.flatio.repository.CurrencyRepository;
import com.flatio.repository.ListingRepository;
import com.flatio.repository.PriceHistoryRepository;
import com.flatio.repository.SourceRepository;
import com.flatio.repository.UserRepository;
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
import org.springframework.test.util.ReflectionTestUtils;
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

  @Autowired
  private UserRepository userRepository;

  @Autowired
  private BlacklistEntryRepository blacklistEntryRepository;

  private ListingServiceImpl listingService;
  private ListingMapper listingMapper;
  private CurrencyRateService currencyRateService;

  @BeforeEach
  void setUp() {
    listingMapper = mock(ListingMapper.class);
    when(listingMapper.toSummaryResponse(ArgumentMatchers.any()))
        .thenReturn(mock(ListingSummaryResponse.class));
    currencyRateService = mock(CurrencyRateService.class);
    when(currencyRateService.getUsdToByn()).thenReturn(Optional.empty());
    listingService = new ListingServiceImpl(
        listingRepository, priceHistoryRepository, sourceRepository, currencyRepository, listingMapper, currencyRateService);
    ReflectionTestUtils.setField(listingService, "ftsLanguage", "russian");
  }

  // -------------------------------------------------------------------------
  // buildSearchSpec — price filter bypass for USD listings without BYN conversion,
  // no live NBRB rate available either (#264)
  // -------------------------------------------------------------------------

  @Test
  void should_include_usd_listing_without_byn_conversion_when_price_filter_is_set_and_no_rate_available() {
    // Given — Realt listing priced in USD; NBRB rate was unavailable at ingestion so priceByn=null,
    // and (per this class's default currencyRateService stub) no live rate is available either.
    // Previously, COALESCE(null, 250) = 250 was compared against BYN range [500..2000]
    // and incorrectly excluded the listing; the current behaviour is to include it rather than
    // compare an unconverted USD amount against a BYN range (see #528 below for when a live rate
    // *is* available — then the listing is correctly converted and compared instead).
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
    var result = listingService.search(criteria, PageRequest.of(0, 10), null, null);

    // Then — USD listing without BYN conversion bypasses the BYN price filter
    assertThat(result.getTotalElements()).isEqualTo(1);
  }

  // -------------------------------------------------------------------------
  // buildSearchSpec — USD listings without a stored priceByn are correctly filtered
  // once a live NBRB rate is available (#528)
  // -------------------------------------------------------------------------

  @Test
  void should_exclude_usd_listing_without_byn_conversion_when_outside_range_and_rate_available() {
    // Given — same "priceByn=null at ingestion" scenario as #264, but a live rate is now
    // available (e.g. next scheduled sync succeeded): $650 * 3.0792 ≈ 2 001.48 BYN, just outside
    // the requested [1000..2000] BYN range — must no longer bypass the filter
    when(currencyRateService.getUsdToByn()).thenReturn(Optional.of(BigDecimal.valueOf(3.0792)));
    var source = sourceRepository.findByCode("REALT").orElseThrow();
    var usdCurrency = currencyRepository.findByCode("USD").orElseThrow();
    var country = countryRepository.findByCode("BY").orElseThrow();

    var listing = new Listing();
    listing.setExternalId("ext-usd-no-byn-528-outside");
    listing.setSource(source);
    listing.setTitle("USD listing — out of range once converted");
    listing.setDealType(DealType.RENT);
    listing.setPrice(BigDecimal.valueOf(650));
    listing.setCurrency(usdCurrency);
    listing.setPriceByn(null);
    listing.setCountry(country);
    listing.setStatus(ListingStatus.ACTIVE);
    listing.setSourceUrl("https://realt.by/ext-usd-no-byn-528-outside");
    listingRepository.saveAndFlush(listing);

    var criteria = new ListingSearchCriteria(
        null, null, null, null, null,
        BigDecimal.valueOf(1_000), BigDecimal.valueOf(2_000),
        null, null, null, null
    );

    // When
    var result = listingService.search(criteria, PageRequest.of(0, 10), null, null);

    // Then — no longer bypasses the filter; correctly excluded once converted
    assertThat(result.getTotalElements()).isZero();
  }

  @Test
  void should_include_usd_listing_without_byn_conversion_when_inside_range_and_rate_available() {
    // Given — same scenario, but the converted price falls inside the range: $500 * 3.0792 ≈
    // 1 539.60 BYN, inside [1000..2000]
    when(currencyRateService.getUsdToByn()).thenReturn(Optional.of(BigDecimal.valueOf(3.0792)));
    var source = sourceRepository.findByCode("REALT").orElseThrow();
    var usdCurrency = currencyRepository.findByCode("USD").orElseThrow();
    var country = countryRepository.findByCode("BY").orElseThrow();

    var listing = new Listing();
    listing.setExternalId("ext-usd-no-byn-528-inside");
    listing.setSource(source);
    listing.setTitle("USD listing — in range once converted");
    listing.setDealType(DealType.RENT);
    listing.setPrice(BigDecimal.valueOf(500));
    listing.setCurrency(usdCurrency);
    listing.setPriceByn(null);
    listing.setCountry(country);
    listing.setStatus(ListingStatus.ACTIVE);
    listing.setSourceUrl("https://realt.by/ext-usd-no-byn-528-inside");
    listingRepository.saveAndFlush(listing);

    var criteria = new ListingSearchCriteria(
        null, null, null, null, null,
        BigDecimal.valueOf(1_000), BigDecimal.valueOf(2_000),
        null, null, null, null
    );

    // When
    var result = listingService.search(criteria, PageRequest.of(0, 10), null, null);

    // Then — correctly included once converted and compared
    assertThat(result.getTotalElements()).isEqualTo(1);
  }

  @Test
  void should_use_stored_price_byn_instead_of_recomputing_when_rate_available() {
    // Given — priceByn already stored (rate was available at ingestion) at 3_000, well outside
    // the requested range; a live rate is also available now but must not override the stored
    // conversion — the stored value is authoritative once present
    when(currencyRateService.getUsdToByn()).thenReturn(Optional.of(BigDecimal.valueOf(3.0792)));
    var source = sourceRepository.findByCode("REALT").orElseThrow();
    var usdCurrency = currencyRepository.findByCode("USD").orElseThrow();
    var country = countryRepository.findByCode("BY").orElseThrow();

    var listing = new Listing();
    listing.setExternalId("ext-usd-with-byn-528");
    listing.setSource(source);
    listing.setTitle("USD listing — stored priceByn out of range");
    listing.setDealType(DealType.RENT);
    listing.setPrice(BigDecimal.valueOf(500));
    listing.setCurrency(usdCurrency);
    listing.setPriceByn(BigDecimal.valueOf(3_000));
    listing.setCountry(country);
    listing.setStatus(ListingStatus.ACTIVE);
    listing.setSourceUrl("https://realt.by/ext-usd-with-byn-528");
    listingRepository.saveAndFlush(listing);

    var criteria = new ListingSearchCriteria(
        null, null, null, null, null,
        BigDecimal.valueOf(1_000), BigDecimal.valueOf(2_000),
        null, null, null, null
    );

    // When
    var result = listingService.search(criteria, PageRequest.of(0, 10), null, null);

    // Then — excluded based on the stored priceByn (3 000), not a recomputed 1 539.60
    assertThat(result.getTotalElements()).isZero();
  }

  @Test
  void should_include_non_usd_non_byn_listing_without_byn_conversion_when_rate_available() {
    // Given — a hypothetical EUR-priced listing without a stored priceByn (no connector produces
    // this today). A live USD rate is available, but this method only converts USD, so the raw
    // EUR amount must not be silently compared as if it were already BYN — same graceful bypass
    // as the "no rate at all" case, not a wrong exclusion/inclusion based on an unconverted amount
    when(currencyRateService.getUsdToByn()).thenReturn(Optional.of(BigDecimal.valueOf(3.0792)));
    var source = sourceRepository.findByCode("REALT").orElseThrow();
    var eurCurrency = currencyRepository.findByCode("EUR").orElseThrow();
    var country = countryRepository.findByCode("BY").orElseThrow();

    var listing = new Listing();
    listing.setExternalId("ext-eur-no-byn-528");
    listing.setSource(source);
    listing.setTitle("EUR listing — no BYN conversion, unsupported currency for on-the-fly conversion");
    listing.setDealType(DealType.RENT);
    listing.setPrice(BigDecimal.valueOf(250));
    listing.setCurrency(eurCurrency);
    listing.setPriceByn(null);
    listing.setCountry(country);
    listing.setStatus(ListingStatus.ACTIVE);
    listing.setSourceUrl("https://realt.by/ext-eur-no-byn-528");
    listingRepository.saveAndFlush(listing);

    // Raw 250 would fall inside [1000..2000] only by coincidence of being compared unconverted;
    // this asserts the listing is included via the bypass, not via a wrong raw-amount comparison
    var criteria = new ListingSearchCriteria(
        null, null, null, null, null,
        BigDecimal.valueOf(1_000), BigDecimal.valueOf(2_000),
        null, null, null, null
    );

    // When
    var result = listingService.search(criteria, PageRequest.of(0, 10), null, null);

    // Then — bypasses the filter (included) rather than being wrongly excluded by raw-amount comparison
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
    var result = listingService.search(criteria, PageRequest.of(0, 10), null, null);

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
    var result = listingService.search(criteria, PageRequest.of(0, 10), null, null);

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
    assertThatNoException().isThrownBy(() -> listingService.search(criteria, PageRequest.of(0, 10), null, null));
  }

  // -------------------------------------------------------------------------
  // buildSearchSpec — LIKE special characters in city are escaped (#388)
  // -------------------------------------------------------------------------

  @Test
  void should_match_city_literally_when_it_contains_like_wildcard_characters() {
    // Given — one listing whose city literally contains "_", one whose city would also match
    // "d_town" only if the underscore were left as an unescaped LIKE wildcard
    var source = sourceRepository.findByCode("ONLINER").orElseThrow();
    var currency = currencyRepository.findByCode("BYN").orElseThrow();
    var country = countryRepository.findByCode("BY").orElseThrow();

    var literalMatch = new Listing();
    literalMatch.setExternalId("ext-city-escape-388-1");
    literalMatch.setSource(source);
    literalMatch.setTitle("Listing in literal city");
    literalMatch.setDealType(DealType.RENT);
    literalMatch.setPrice(BigDecimal.valueOf(500));
    literalMatch.setCurrency(currency);
    literalMatch.setCountry(country);
    literalMatch.setStatus(ListingStatus.ACTIVE);
    literalMatch.setCity("d_town");
    literalMatch.setSourceUrl("https://onliner.by/ext-city-escape-388-1");
    listingRepository.saveAndFlush(literalMatch);

    var wildcardDecoy = new Listing();
    wildcardDecoy.setExternalId("ext-city-escape-388-2");
    wildcardDecoy.setSource(source);
    wildcardDecoy.setTitle("Listing in a city that only an unescaped _ wildcard would match");
    wildcardDecoy.setDealType(DealType.RENT);
    wildcardDecoy.setPrice(BigDecimal.valueOf(500));
    wildcardDecoy.setCurrency(currency);
    wildcardDecoy.setCountry(country);
    wildcardDecoy.setStatus(ListingStatus.ACTIVE);
    wildcardDecoy.setCity("dxtown");
    wildcardDecoy.setSourceUrl("https://onliner.by/ext-city-escape-388-2");
    listingRepository.saveAndFlush(wildcardDecoy);

    var criteria = new ListingSearchCriteria(
        null, null, null, "d_town", null, null, null, null, null, null, null
    );

    // When
    var result = listingService.search(criteria, PageRequest.of(0, 10), null, null);

    // Then — only the literal "d_town" match, "dxtown" is correctly excluded
    assertThat(result.getTotalElements()).isEqualTo(1);
  }

  @Test
  void should_match_city_literally_in_full_text_search_path_when_it_contains_like_wildcard_characters() {
    // Given — same escaping requirement as buildSearchSpec, but through the native-SQL
    // fullTextSearch query (searchWithFts), triggered here by a non-blank criteria.query()
    var source = sourceRepository.findByCode("ONLINER").orElseThrow();
    var currency = currencyRepository.findByCode("BYN").orElseThrow();
    var country = countryRepository.findByCode("BY").orElseThrow();

    var literalMatch = new Listing();
    literalMatch.setExternalId("ext-city-escape-388-fts-1");
    literalMatch.setSource(source);
    literalMatch.setTitle("Квартира в тестовом районе");
    literalMatch.setDealType(DealType.RENT);
    literalMatch.setPrice(BigDecimal.valueOf(500));
    literalMatch.setCurrency(currency);
    literalMatch.setCountry(country);
    literalMatch.setStatus(ListingStatus.ACTIVE);
    literalMatch.setCity("d_town");
    literalMatch.setSourceUrl("https://onliner.by/ext-city-escape-388-fts-1");
    listingRepository.saveAndFlush(literalMatch);

    var wildcardDecoy = new Listing();
    wildcardDecoy.setExternalId("ext-city-escape-388-fts-2");
    wildcardDecoy.setSource(source);
    wildcardDecoy.setTitle("Квартира в тестовом районе");
    wildcardDecoy.setDealType(DealType.RENT);
    wildcardDecoy.setPrice(BigDecimal.valueOf(500));
    wildcardDecoy.setCurrency(currency);
    wildcardDecoy.setCountry(country);
    wildcardDecoy.setStatus(ListingStatus.ACTIVE);
    wildcardDecoy.setCity("dxtown");
    wildcardDecoy.setSourceUrl("https://onliner.by/ext-city-escape-388-fts-2");
    listingRepository.saveAndFlush(wildcardDecoy);

    var criteria = new ListingSearchCriteria(
        null, null, null, "d_town", null, null, null, null, null, "квартира", null
    );

    // When
    var result = listingService.search(criteria, PageRequest.of(0, 10), null, null);

    // Then — only the literal "d_town" match, "dxtown" is correctly excluded
    assertThat(result.getTotalElements()).isEqualTo(1);
  }

  // -------------------------------------------------------------------------
  // buildSearchSpec — blacklist exclusion (issue #414)
  // -------------------------------------------------------------------------

  @Test
  void should_exclude_blacklisted_listing_from_default_search() {
    // Given
    var user = userRepository.save(buildUser());
    var listing = listingRepository.saveAndFlush(buildBasicListing("ext-bl-spec-listing"));
    blacklistEntryRepository.saveAndFlush(
        buildBlacklistEntry(user, BlacklistEntryType.LISTING, listing.getId().toString()));
    var criteria = new ListingSearchCriteria(null, null, null, null, null, null, null, null, null, null, null);

    // When
    var result = listingService.search(criteria, PageRequest.of(0, 10), user.getId(), null);

    // Then
    assertThat(result.getTotalElements()).isZero();
  }

  @Test
  void should_exclude_listing_from_blacklisted_source_in_default_search() {
    // Given
    var user = userRepository.save(buildUser());
    var listing = listingRepository.saveAndFlush(buildBasicListing("ext-bl-spec-source"));
    blacklistEntryRepository.saveAndFlush(
        buildBlacklistEntry(user, BlacklistEntryType.SOURCE, listing.getSource().getCode()));
    var criteria = new ListingSearchCriteria(null, null, null, null, null, null, null, null, null, null, null);

    // When
    var result = listingService.search(criteria, PageRequest.of(0, 10), user.getId(), null);

    // Then
    assertThat(result.getTotalElements()).isZero();
  }

  @Test
  void should_exclude_listing_matching_blacklisted_keyword_in_default_search() {
    // Given
    var user = userRepository.save(buildUser());
    var listing = buildBasicListing("ext-bl-spec-keyword");
    listing.setDescription("содержит стоп-слово в описании");
    listingRepository.saveAndFlush(listing);
    blacklistEntryRepository.saveAndFlush(buildBlacklistEntry(user, BlacklistEntryType.KEYWORD, "стоп-слово"));
    var criteria = new ListingSearchCriteria(null, null, null, null, null, null, null, null, null, null, null);

    // When
    var result = listingService.search(criteria, PageRequest.of(0, 10), user.getId(), null);

    // Then
    assertThat(result.getTotalElements()).isZero();
  }

  @Test
  void should_exclude_listing_when_blacklisted_keyword_contains_like_wildcards_in_default_search() {
    // Given — a stop-word containing literal LIKE wildcard characters must match literally,
    // not be reinterpreted as a wildcard pattern (same class of bug as issue #388)
    var user = userRepository.save(buildUser());
    var listing = buildBasicListing("ext-bl-spec-wildcard");
    listing.setDescription("цена 50%_скидка сегодня");
    listingRepository.saveAndFlush(listing);
    blacklistEntryRepository.saveAndFlush(buildBlacklistEntry(user, BlacklistEntryType.KEYWORD, "50%_скидка"));
    var criteria = new ListingSearchCriteria(null, null, null, null, null, null, null, null, null, null, null);

    // When
    var result = listingService.search(criteria, PageRequest.of(0, 10), user.getId(), null);

    // Then
    assertThat(result.getTotalElements()).isZero();
  }

  @Test
  void should_not_exclude_listing_blacklisted_by_a_different_user_in_default_search() {
    // Given
    var owner = userRepository.save(buildUser());
    var stranger = userRepository.save(buildUser());
    var listing = listingRepository.saveAndFlush(buildBasicListing("ext-bl-spec-other-user"));
    blacklistEntryRepository.saveAndFlush(
        buildBlacklistEntry(stranger, BlacklistEntryType.LISTING, listing.getId().toString()));
    var criteria = new ListingSearchCriteria(null, null, null, null, null, null, null, null, null, null, null);

    // When — the caller (owner) has no blacklist entry for this listing
    var result = listingService.search(criteria, PageRequest.of(0, 10), owner.getId(), null);

    // Then
    assertThat(result.getTotalElements()).isEqualTo(1);
  }

  @Test
  void should_not_apply_blacklist_when_user_id_is_null_in_default_search() {
    // Given — anonymous/user-less caller: userId=null must skip exclusion entirely
    var user = userRepository.save(buildUser());
    var listing = listingRepository.saveAndFlush(buildBasicListing("ext-bl-spec-anon"));
    blacklistEntryRepository.saveAndFlush(
        buildBlacklistEntry(user, BlacklistEntryType.LISTING, listing.getId().toString()));
    var criteria = new ListingSearchCriteria(null, null, null, null, null, null, null, null, null, null, null);

    // When
    var result = listingService.search(criteria, PageRequest.of(0, 10), null, null);

    // Then
    assertThat(result.getTotalElements()).isEqualTo(1);
  }

  @Test
  void should_exclude_blacklisted_listing_in_full_text_search_path() {
    // Given — same exclusion, through the native-SQL fullTextSearch query (searchWithFts),
    // triggered by a non-blank criteria.query()
    var user = userRepository.save(buildUser());
    var listing = buildBasicListing("ext-bl-fts-listing");
    listing.setTitle("Уникальнаяфразадлятеста");
    listingRepository.saveAndFlush(listing);
    blacklistEntryRepository.saveAndFlush(
        buildBlacklistEntry(user, BlacklistEntryType.LISTING, listing.getId().toString()));
    var criteria = new ListingSearchCriteria(
        null, null, null, null, null, null, null, null, null, "уникальнаяфразадлятеста", null);

    // When
    var result = listingService.search(criteria, PageRequest.of(0, 10), user.getId(), null);

    // Then
    assertThat(result.getTotalElements()).isZero();
  }

  private Listing buildBasicListing(String externalId) {
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
    return listing;
  }

  private User buildUser() {
    var user = new User();
    user.setDisplayName("Test User");
    user.setActive(true);
    return user;
  }

  private BlacklistEntry buildBlacklistEntry(User user, BlacklistEntryType type, String value) {
    var entry = new BlacklistEntry();
    entry.setUser(user);
    entry.setType(type);
    entry.setValue(value);
    return entry;
  }
}
