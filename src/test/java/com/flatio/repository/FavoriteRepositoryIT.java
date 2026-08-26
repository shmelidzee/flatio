package com.flatio.repository;

import com.flatio.domain.favorite.Favorite;
import com.flatio.domain.listing.DealType;
import com.flatio.domain.listing.Listing;
import com.flatio.domain.listing.ListingStatus;
import com.flatio.domain.user.User;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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
class FavoriteRepositoryIT {

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
  private FavoriteRepository favoriteRepository;

  @Autowired
  private UserRepository userRepository;

  @Autowired
  private ListingRepository listingRepository;

  @Autowired
  private SourceRepository sourceRepository;

  @Autowired
  private CurrencyRepository currencyRepository;

  @Autowired
  private CountryRepository countryRepository;

  @Autowired
  private TestEntityManager entityManager;

  @BeforeEach
  void setUp() {
    favoriteRepository.deleteAll();
    listingRepository.deleteAll();
    userRepository.deleteAll();
  }

  // -------------------------------------------------------------------------
  // Basic persistence + eager associations (findByUser JOIN FETCH)
  // -------------------------------------------------------------------------

  @Test
  void should_save_and_find_favorite_by_user_with_eager_associations() {
    // Given
    var user = userRepository.save(buildUser("Pavel"));
    var listing = listingRepository.save(buildListing("ext-basic-1", BigDecimal.valueOf(500)));
    favoriteRepository.saveAndFlush(buildFavorite(user, listing, BigDecimal.valueOf(500)));
    entityManager.clear();

    // When
    var result = favoriteRepository.findByUser(user, PageRequest.of(0, 10));

    // Then
    assertThat(result.getContent()).hasSize(1);
    var favorite = result.getContent().get(0);
    assertThat(favorite.getListing().getTitle()).isEqualTo("Test listing ext-basic-1");
    assertThat(favorite.getListing().getSource().getCode()).isEqualTo("ONLINER");
    assertThat(favorite.getListing().getCurrency().getCode()).isEqualTo("BYN");
    assertThat(favorite.getCreatedAt()).isNotNull();
  }

  // -------------------------------------------------------------------------
  // @Formula priceChange — computed by the database, not the application
  // -------------------------------------------------------------------------

  @Test
  void should_compute_negative_price_change_when_current_price_dropped_below_price_at_add() {
    // Given — favorite captured the listing at 100.00, listing price then dropped to 80.00
    var user = userRepository.save(buildUser("Pavel"));
    var listing = listingRepository.save(buildListing("ext-price-drop", BigDecimal.valueOf(100)));
    favoriteRepository.saveAndFlush(buildFavorite(user, listing, BigDecimal.valueOf(100)));

    listing.setPrice(BigDecimal.valueOf(80));
    listingRepository.saveAndFlush(listing);
    entityManager.clear();

    // When — reload from the database so the @Formula column is recomputed, not the stale
    // in-memory value from the persistence context at the time of the insert
    var reloaded = favoriteRepository.findByUserAndListingId(user, listing.getId()).orElseThrow();

    // Then
    assertThat(reloaded.getPriceChange()).isEqualByComparingTo(BigDecimal.valueOf(-20));
  }

  @Test
  void should_compute_positive_price_change_when_current_price_rose_above_price_at_add() {
    // Given — favorite captured the listing at 100.00, listing price then rose to 130.00
    var user = userRepository.save(buildUser("Pavel"));
    var listing = listingRepository.save(buildListing("ext-price-rise", BigDecimal.valueOf(100)));
    favoriteRepository.saveAndFlush(buildFavorite(user, listing, BigDecimal.valueOf(100)));

    listing.setPrice(BigDecimal.valueOf(130));
    listingRepository.saveAndFlush(listing);
    entityManager.clear();

    // When
    var reloaded = favoriteRepository.findByUserAndListingId(user, listing.getId()).orElseThrow();

    // Then
    assertThat(reloaded.getPriceChange()).isEqualByComparingTo(BigDecimal.valueOf(30));
  }

  @Test
  void should_compute_zero_price_change_when_current_price_matches_price_at_add() {
    // Given — listing price never moved since the favorite was created
    var user = userRepository.save(buildUser("Pavel"));
    var listing = listingRepository.save(buildListing("ext-price-flat", BigDecimal.valueOf(100)));
    favoriteRepository.saveAndFlush(buildFavorite(user, listing, BigDecimal.valueOf(100)));
    entityManager.clear();

    // When
    var reloaded = favoriteRepository.findByUserAndListingId(user, listing.getId()).orElseThrow();

    // Then
    assertThat(reloaded.getPriceChange()).isEqualByComparingTo(BigDecimal.ZERO);
  }

  // -------------------------------------------------------------------------
  // Sorting by the @Formula column through the JOIN FETCH + Pageable query
  // -------------------------------------------------------------------------

  @Test
  void should_sort_favorites_by_price_change_when_paginated_through_join_fetch_query() {
    // Given — three favorites of the same user with distinct price deltas: -50, 0, +30
    var user = userRepository.save(buildUser("Pavel"));
    var listing = listingRepository.save(buildListing("ext-sort-common", BigDecimal.valueOf(100)));
    var dropped = favoriteRepository.save(buildFavorite(user, listing, BigDecimal.valueOf(150)));
    var unchanged = favoriteRepository.save(buildFavorite(user,
        listingRepository.save(buildListing("ext-sort-flat", BigDecimal.valueOf(100))), BigDecimal.valueOf(100)));
    var risen = favoriteRepository.save(buildFavorite(user,
        listingRepository.save(buildListing("ext-sort-risen", BigDecimal.valueOf(100))), BigDecimal.valueOf(70)));
    favoriteRepository.flush();
    entityManager.clear();

    // When — JOIN FETCH query combined with Pageable and a Sort on the formula column
    var page = favoriteRepository.findByUser(user, PageRequest.of(0, 10, Sort.by(Sort.Direction.ASC, "priceChange")));

    // Then — the JOIN FETCH on listing/source/currency does not break pagination or sorting on
    // the formula column, and total count remains correct (separate countQuery)
    assertThat(page.getTotalElements()).isEqualTo(3);
    assertThat(page.getContent()).extracting(Favorite::getId)
        .containsExactly(dropped.getId(), unchanged.getId(), risen.getId());
    assertThat(page.getContent()).extracting(f -> f.getPriceChange().intValue())
        .containsExactly(-50, 0, 30);
  }

  // -------------------------------------------------------------------------
  // UNIQUE(user_id, listing_id)
  // -------------------------------------------------------------------------

  @Test
  void should_throw_exception_when_duplicate_favorite_persisted() {
    // Given — a favorite already exists for this (user, listing) pair
    var user = userRepository.save(buildUser("Pavel"));
    var listing = listingRepository.save(buildListing("ext-dup-fav", BigDecimal.valueOf(100)));
    favoriteRepository.saveAndFlush(buildFavorite(user, listing, BigDecimal.valueOf(100)));

    // When / Then — a second favorite for the same (user, listing) violates the unique constraint
    var duplicate = buildFavorite(user, listing, BigDecimal.valueOf(100));
    assertThatThrownBy(() -> favoriteRepository.saveAndFlush(duplicate))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  // -------------------------------------------------------------------------
  // findByUser — per-user isolation
  // -------------------------------------------------------------------------

  @Test
  void should_return_only_own_favorites_for_each_user() {
    // Given
    var user1 = userRepository.save(buildUser("Pavel"));
    var user2 = userRepository.save(buildUser("Anna"));
    var listing1 = listingRepository.save(buildListing("ext-own-1", BigDecimal.valueOf(100)));
    var listing2 = listingRepository.save(buildListing("ext-own-2", BigDecimal.valueOf(200)));
    favoriteRepository.save(buildFavorite(user1, listing1, BigDecimal.valueOf(100)));
    favoriteRepository.save(buildFavorite(user2, listing2, BigDecimal.valueOf(200)));
    favoriteRepository.flush();
    entityManager.clear();

    // When
    var result = favoriteRepository.findByUser(user1, PageRequest.of(0, 10));

    // Then — user1 sees only their own favorite, never user2's
    assertThat(result.getContent()).hasSize(1);
    assertThat(result.getContent().get(0).getListing().getExternalId()).isEqualTo("ext-own-1");
  }

  // -------------------------------------------------------------------------
  // findByUserAndListingId
  // -------------------------------------------------------------------------

  @Test
  void should_return_empty_when_favorite_belongs_to_another_user() {
    // Given
    var owner = userRepository.save(buildUser("Pavel"));
    var stranger = userRepository.save(buildUser("Anna"));
    var listing = listingRepository.save(buildListing("ext-stranger", BigDecimal.valueOf(100)));
    favoriteRepository.saveAndFlush(buildFavorite(owner, listing, BigDecimal.valueOf(100)));

    // When — stranger tries to look up owner's favorite
    var result = favoriteRepository.findByUserAndListingId(stranger, listing.getId());

    // Then
    assertThat(result).isEmpty();
  }

  // -------------------------------------------------------------------------
  // countByUser
  // -------------------------------------------------------------------------

  @Test
  void should_count_only_favorites_owned_by_user() {
    // Given
    var user1 = userRepository.save(buildUser("Pavel"));
    var user2 = userRepository.save(buildUser("Anna"));
    var listing1 = listingRepository.save(buildListing("ext-count-1", BigDecimal.valueOf(100)));
    var listing2 = listingRepository.save(buildListing("ext-count-2", BigDecimal.valueOf(100)));
    var listing3 = listingRepository.save(buildListing("ext-count-3", BigDecimal.valueOf(100)));
    favoriteRepository.save(buildFavorite(user1, listing1, BigDecimal.valueOf(100)));
    favoriteRepository.save(buildFavorite(user1, listing2, BigDecimal.valueOf(100)));
    favoriteRepository.save(buildFavorite(user2, listing3, BigDecimal.valueOf(100)));
    favoriteRepository.flush();

    // When
    var count = favoriteRepository.countByUser(user1);

    // Then
    assertThat(count).isEqualTo(2);
  }

  // -------------------------------------------------------------------------
  // helpers
  // -------------------------------------------------------------------------

  private User buildUser(String displayName) {
    var user = new User();
    user.setDisplayName(displayName);
    user.setActive(true);
    return user;
  }

  private Listing buildListing(String externalId, BigDecimal price) {
    var source = sourceRepository.findByCode("ONLINER").orElseThrow();
    var currency = currencyRepository.findByCode("BYN").orElseThrow();
    var country = countryRepository.findByCode("BY").orElseThrow();

    var listing = new Listing();
    listing.setExternalId(externalId);
    listing.setSource(source);
    listing.setTitle("Test listing " + externalId);
    listing.setDealType(DealType.RENT);
    listing.setPrice(price);
    listing.setCurrency(currency);
    listing.setCountry(country);
    listing.setStatus(ListingStatus.ACTIVE);
    listing.setSourceUrl("https://onliner.by/listings/" + externalId);
    return listing;
  }

  private Favorite buildFavorite(User user, Listing listing, BigDecimal priceAtAdd) {
    var favorite = new Favorite();
    favorite.setUser(user);
    favorite.setListing(listing);
    favorite.setPriceAtAdd(priceAtAdd);
    return favorite;
  }
}
