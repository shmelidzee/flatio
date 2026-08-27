package com.flatio.service.impl;

import com.flatio.domain.blacklist.BlacklistEntry;
import com.flatio.domain.blacklist.BlacklistEntryType;
import com.flatio.domain.city.City;
import com.flatio.domain.listing.DealType;
import com.flatio.domain.listing.Listing;
import com.flatio.domain.listing.ListingStatus;
import com.flatio.domain.notification.Notification;
import com.flatio.domain.source.Source;
import com.flatio.domain.subscription.Subscription;
import com.flatio.domain.subscription.TriggerType;
import com.flatio.domain.user.User;
import com.flatio.repository.BlacklistEntryRepository;
import com.flatio.repository.CityRepository;
import com.flatio.repository.NotificationRepository;
import com.flatio.repository.SubscriptionRepository;
import com.flatio.service.domain.ListingChange;
import com.flatio.service.domain.ListingChangeType;
import com.flatio.web.dto.SubscriptionSearchCriteria;
import com.flatio.web.mapper.SearchCriteriaJsonMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationTriggerServiceImplTest {

  @Mock
  private SubscriptionRepository subscriptionRepository;

  @Mock
  private NotificationRepository notificationRepository;

  @Mock
  private CityRepository cityRepository;

  @Mock
  private BlacklistEntryRepository blacklistEntryRepository;

  @Mock
  private SearchCriteriaJsonMapper searchCriteriaJsonMapper;

  @Mock
  private NotificationCreator notificationCreator;

  private NotificationTriggerServiceImpl notificationTriggerService;

  @BeforeEach
  void setUp() {
    // Default: no user has any blacklist entries. Overridden per-test where blacklist matching
    // itself is under test.
    lenient().when(blacklistEntryRepository.findByUserIn(any())).thenReturn(List.of());
    notificationTriggerService = new NotificationTriggerServiceImpl(
        subscriptionRepository, notificationRepository, cityRepository, blacklistEntryRepository,
        searchCriteriaJsonMapper, notificationCreator
    );
  }

  // -------------------------------------------------------------------------
  // NEW_LISTING
  // -------------------------------------------------------------------------

  @Test
  void should_create_pending_notification_when_new_listing_matches_active_subscription() {
    // Given
    var listing = buildListing(1L, ListingStatus.ACTIVE, DealType.RENT, BigDecimal.valueOf(1000), "Минск");
    var subscription = buildSubscription(10L, Set.of(TriggerType.NEW_LISTING));
    var change = new ListingChange(listing, ListingChangeType.NEW, null, null, null);
    when(subscriptionRepository.findByActiveTrue()).thenReturn(List.of(subscription));
    when(searchCriteriaJsonMapper.toCriteria(subscription.getSearchCriteria())).thenReturn(matchAllCriteria());

    // When
    notificationTriggerService.evaluate(List.of(change));

    // Then
    verify(notificationCreator).create(subscription, listing, TriggerType.NEW_LISTING);
  }

  @Test
  void should_not_create_notification_when_trigger_not_enabled_on_subscription() {
    // Given — subscription only listens for PRICE_DROP, not NEW_LISTING
    var listing = buildListing(1L, ListingStatus.ACTIVE, DealType.RENT, BigDecimal.valueOf(1000), "Минск");
    var subscription = buildSubscription(10L, Set.of(TriggerType.PRICE_DROP));
    var change = new ListingChange(listing, ListingChangeType.NEW, null, null, null);
    when(subscriptionRepository.findByActiveTrue()).thenReturn(List.of(subscription));
    when(searchCriteriaJsonMapper.toCriteria(subscription.getSearchCriteria())).thenReturn(matchAllCriteria());

    // When
    notificationTriggerService.evaluate(List.of(change));

    // Then
    verify(notificationCreator, never()).create(any(), any(), any());
  }

  @Test
  void should_not_create_notification_when_subscription_filter_does_not_match() {
    // Given — subscription filters for SELL deals, listing is a RENT
    var listing = buildListing(1L, ListingStatus.ACTIVE, DealType.RENT, BigDecimal.valueOf(1000), "Минск");
    var subscription = buildSubscription(10L, Set.of(TriggerType.NEW_LISTING));
    var criteria = new SubscriptionSearchCriteria(
        DealType.SELL, null, null, null, null, null, null, null, null, null);
    var change = new ListingChange(listing, ListingChangeType.NEW, null, null, null);
    when(subscriptionRepository.findByActiveTrue()).thenReturn(List.of(subscription));
    when(searchCriteriaJsonMapper.toCriteria(subscription.getSearchCriteria())).thenReturn(criteria);

    // When
    notificationTriggerService.evaluate(List.of(change));

    // Then
    verify(notificationCreator, never()).create(any(), any(), any());
  }

  @Test
  void should_ignore_paused_subscription_when_evaluating_change() {
    // Given — no active subscriptions returned (paused subscription excluded at the repository level)
    var listing = buildListing(1L, ListingStatus.ACTIVE, DealType.RENT, BigDecimal.valueOf(1000), "Минск");
    var change = new ListingChange(listing, ListingChangeType.NEW, null, null, null);
    when(subscriptionRepository.findByActiveTrue()).thenReturn(List.of());

    // When
    notificationTriggerService.evaluate(List.of(change));

    // Then
    verify(notificationCreator, never()).create(any(), any(), any());
  }

  @Test
  void should_do_nothing_when_changes_list_is_empty() {
    // Given — no listing changes to evaluate

    // When
    notificationTriggerService.evaluate(List.of());

    // Then
    verify(subscriptionRepository, never()).findByActiveTrue();
    verify(notificationCreator, never()).create(any(), any(), any());
  }

  // -------------------------------------------------------------------------
  // PRICE_DROP
  // -------------------------------------------------------------------------

  @Test
  void should_create_notification_when_price_drop_meets_threshold() {
    // Given — price fell from 1000 to 900, a 10% drop, threshold is 5%
    var listing = buildListing(1L, ListingStatus.ACTIVE, DealType.RENT, BigDecimal.valueOf(900), "Минск");
    var subscription = buildSubscription(10L, Set.of(TriggerType.PRICE_DROP));
    subscription.setPriceDropThreshold(new BigDecimal("5.00"));
    var change = new ListingChange(listing, ListingChangeType.UPDATED, ListingStatus.ACTIVE,
        BigDecimal.valueOf(1000), BigDecimal.valueOf(900));
    when(subscriptionRepository.findByActiveTrue()).thenReturn(List.of(subscription));
    when(searchCriteriaJsonMapper.toCriteria(subscription.getSearchCriteria())).thenReturn(matchAllCriteria());

    // When
    notificationTriggerService.evaluate(List.of(change));

    // Then
    verify(notificationCreator).create(subscription, listing, TriggerType.PRICE_DROP);
  }

  @Test
  void should_not_create_notification_when_price_drop_below_threshold() {
    // Given — price fell from 1000 to 980, a 2% drop, threshold is 5%
    var listing = buildListing(1L, ListingStatus.ACTIVE, DealType.RENT, BigDecimal.valueOf(980), "Минск");
    var subscription = buildSubscription(10L, Set.of(TriggerType.PRICE_DROP));
    subscription.setPriceDropThreshold(new BigDecimal("5.00"));
    var change = new ListingChange(listing, ListingChangeType.UPDATED, ListingStatus.ACTIVE,
        BigDecimal.valueOf(1000), BigDecimal.valueOf(980));
    when(subscriptionRepository.findByActiveTrue()).thenReturn(List.of(subscription));
    when(searchCriteriaJsonMapper.toCriteria(subscription.getSearchCriteria())).thenReturn(matchAllCriteria());

    // When
    notificationTriggerService.evaluate(List.of(change));

    // Then
    verify(notificationCreator, never()).create(any(), any(), any());
  }

  // -------------------------------------------------------------------------
  // REPOSTED
  // -------------------------------------------------------------------------

  @Test
  void should_prioritize_reposted_over_new_listing_and_price_drop() {
    // Given — listing is REPOSTED; subscription listens for all three triggers
    var listing = buildListing(1L, ListingStatus.REPOSTED, DealType.RENT, BigDecimal.valueOf(900), "Минск");
    var subscription = buildSubscription(10L,
        Set.of(TriggerType.NEW_LISTING, TriggerType.PRICE_DROP, TriggerType.REPOSTED));
    subscription.setPriceDropThreshold(new BigDecimal("5.00"));
    var change = new ListingChange(listing, ListingChangeType.UPDATED, ListingStatus.ACTIVE,
        BigDecimal.valueOf(1000), BigDecimal.valueOf(900));
    when(subscriptionRepository.findByActiveTrue()).thenReturn(List.of(subscription));
    when(searchCriteriaJsonMapper.toCriteria(subscription.getSearchCriteria())).thenReturn(matchAllCriteria());

    // When
    notificationTriggerService.evaluate(List.of(change));

    // Then — only one notification is created, and it is REPOSTED
    verify(notificationCreator, times(1)).create(subscription, listing, TriggerType.REPOSTED);
    verify(notificationCreator, never()).create(subscription, listing, TriggerType.NEW_LISTING);
    verify(notificationCreator, never()).create(subscription, listing, TriggerType.PRICE_DROP);
  }

  // -------------------------------------------------------------------------
  // REACTIVATED
  // -------------------------------------------------------------------------

  @Test
  void should_create_notification_when_listing_reactivated() {
    // Given — listing transitions from INACTIVE to ACTIVE
    var listing = buildListing(1L, ListingStatus.ACTIVE, DealType.RENT, BigDecimal.valueOf(1000), "Минск");
    var subscription = buildSubscription(10L, Set.of(TriggerType.REACTIVATED));
    var change = new ListingChange(listing, ListingChangeType.UPDATED, ListingStatus.INACTIVE, null, null);
    when(subscriptionRepository.findByActiveTrue()).thenReturn(List.of(subscription));
    when(searchCriteriaJsonMapper.toCriteria(subscription.getSearchCriteria())).thenReturn(matchAllCriteria());

    // When
    notificationTriggerService.evaluate(List.of(change));

    // Then
    verify(notificationCreator).create(subscription, listing, TriggerType.REACTIVATED);
  }

  // -------------------------------------------------------------------------
  // Deduplication
  // -------------------------------------------------------------------------

  @Test
  void should_not_create_duplicate_notification_when_notification_already_exists() {
    // Given — a NEW_LISTING notification for this exact subscription/listing/trigger already exists
    var listing = buildListing(1L, ListingStatus.ACTIVE, DealType.RENT, BigDecimal.valueOf(1000), "Минск");
    var subscription = buildSubscription(10L, Set.of(TriggerType.NEW_LISTING));
    var change = new ListingChange(listing, ListingChangeType.NEW, null, null, null);
    var existingNotification = new Notification();
    existingNotification.setSubscription(subscription);
    existingNotification.setListing(listing);
    existingNotification.setTriggerType(TriggerType.NEW_LISTING);
    when(subscriptionRepository.findByActiveTrue()).thenReturn(List.of(subscription));
    when(searchCriteriaJsonMapper.toCriteria(subscription.getSearchCriteria())).thenReturn(matchAllCriteria());
    when(notificationRepository.findBySubscriptionInAndListingInAndTriggerTypeIn(any(), any(), any()))
        .thenReturn(List.of(existingNotification));

    // When
    notificationTriggerService.evaluate(List.of(change));

    // Then
    verify(notificationCreator, never()).create(any(), any(), any());
  }

  // -------------------------------------------------------------------------
  // Error isolation — criteria resolution
  // -------------------------------------------------------------------------

  @Test
  void should_skip_subscription_and_continue_when_search_criteria_resolution_fails() {
    // Given — subscription 10 has malformed search_criteria JSON, subscription 20 is healthy
    var listing = buildListing(1L, ListingStatus.ACTIVE, DealType.RENT, BigDecimal.valueOf(1000), "Минск");
    var brokenSubscription = buildSubscription(10L, Set.of(TriggerType.NEW_LISTING));
    var healthySubscription = buildSubscription(20L, Set.of(TriggerType.NEW_LISTING));
    var change = new ListingChange(listing, ListingChangeType.NEW, null, null, null);
    when(subscriptionRepository.findByActiveTrue()).thenReturn(List.of(brokenSubscription, healthySubscription));
    when(searchCriteriaJsonMapper.toCriteria(brokenSubscription.getSearchCriteria()))
        .thenThrow(new RuntimeException("malformed search criteria JSON"));
    when(searchCriteriaJsonMapper.toCriteria(healthySubscription.getSearchCriteria()))
        .thenReturn(matchAllCriteria());

    // When
    notificationTriggerService.evaluate(List.of(change));

    // Then — only the healthy subscription is notified, the batch does not fail as a whole
    verify(notificationCreator).create(healthySubscription, listing, TriggerType.NEW_LISTING);
    verify(notificationCreator, never()).create(eq(brokenSubscription), any(), any());
  }

  // -------------------------------------------------------------------------
  // Error isolation — concurrent notification creation
  // -------------------------------------------------------------------------

  @Test
  void should_skip_candidate_and_continue_when_notification_creator_throws_duplicate_key_exception() {
    // Given — two listings match the same subscription's NEW_LISTING trigger, and a concurrent
    // evaluate run races to insert the first notification first
    var firstListing = buildListing(1L, ListingStatus.ACTIVE, DealType.RENT, BigDecimal.valueOf(1000), "Минск");
    var secondListing = buildListing(2L, ListingStatus.ACTIVE, DealType.RENT, BigDecimal.valueOf(1000), "Минск");
    var subscription = buildSubscription(10L, Set.of(TriggerType.NEW_LISTING));
    var firstChange = new ListingChange(firstListing, ListingChangeType.NEW, null, null, null);
    var secondChange = new ListingChange(secondListing, ListingChangeType.NEW, null, null, null);
    when(subscriptionRepository.findByActiveTrue()).thenReturn(List.of(subscription));
    when(searchCriteriaJsonMapper.toCriteria(subscription.getSearchCriteria())).thenReturn(matchAllCriteria());
    doThrow(new DataIntegrityViolationException("duplicate key"))
        .doNothing()
        .when(notificationCreator).create(any(), any(), any());

    // When / Then — evaluate() itself does not propagate the exception
    assertThatNoException().isThrownBy(
        () -> notificationTriggerService.evaluate(List.of(firstChange, secondChange)));

    // Then — both candidates were attempted despite the first one racing and failing
    verify(notificationCreator, times(2)).create(any(), any(), any());
  }

  // -------------------------------------------------------------------------
  // matchesCriteria — filter branch coverage
  // -------------------------------------------------------------------------

  @Test
  void should_create_notification_when_all_optional_criteria_match() {
    // Given — every optional filter is set and matches the listing
    var listing = buildFullyMatchingListing();
    var subscription = buildSubscription(10L, Set.of(TriggerType.NEW_LISTING));
    var change = new ListingChange(listing, ListingChangeType.NEW, null, null, null);
    when(subscriptionRepository.findByActiveTrue()).thenReturn(List.of(subscription));
    when(searchCriteriaJsonMapper.toCriteria(subscription.getSearchCriteria())).thenReturn(fullyMatchingCriteria());

    // When
    notificationTriggerService.evaluate(List.of(change));

    // Then
    verify(notificationCreator).create(subscription, listing, TriggerType.NEW_LISTING);
  }

  @Test
  void should_not_match_when_property_type_differs() {
    // Given
    var listing = buildFullyMatchingListing();
    var criteria = withPropertyType(fullyMatchingCriteria(), "HOUSE");

    // When / Then
    assertNoNotificationForCriteria(listing, criteria);
  }

  @Test
  void should_not_match_when_source_differs() {
    // Given
    var listing = buildFullyMatchingListing();
    var criteria = withSourceId(fullyMatchingCriteria(), "kufar");

    // When / Then
    assertNoNotificationForCriteria(listing, criteria);
  }

  @Test
  void should_not_match_when_city_differs() {
    // Given
    var listing = buildFullyMatchingListing();
    var criteria = withCity(fullyMatchingCriteria(), "Гродно");

    // When / Then
    assertNoNotificationForCriteria(listing, criteria);
  }

  @Test
  void should_match_when_city_filter_resolved_via_city_id() {
    // Given — subscription filters by cityId instead of a raw city name
    var listing = buildFullyMatchingListing();
    var subscription = buildSubscription(10L, Set.of(TriggerType.NEW_LISTING));
    var criteria = withCityId(withCity(fullyMatchingCriteria(), null), 5L);
    var change = new ListingChange(listing, ListingChangeType.NEW, null, null, null);
    var city = new City();
    city.setId(5L);
    city.setNameRu("Минск");
    when(subscriptionRepository.findByActiveTrue()).thenReturn(List.of(subscription));
    when(searchCriteriaJsonMapper.toCriteria(subscription.getSearchCriteria())).thenReturn(criteria);
    when(cityRepository.findAllById(List.of(5L))).thenReturn(List.of(city));

    // When
    notificationTriggerService.evaluate(List.of(change));

    // Then
    verify(notificationCreator).create(subscription, listing, TriggerType.NEW_LISTING);
  }

  @Test
  void should_not_match_when_price_below_minimum() {
    // Given — listing price is 1000, criteria requires at least 2000
    var listing = buildFullyMatchingListing();
    var criteria = withPriceMin(fullyMatchingCriteria(), BigDecimal.valueOf(2000));

    // When / Then
    assertNoNotificationForCriteria(listing, criteria);
  }

  @Test
  void should_not_match_when_price_above_maximum() {
    // Given — listing price is 1000, criteria caps at 500
    var listing = buildFullyMatchingListing();
    var criteria = withPriceMax(fullyMatchingCriteria(), BigDecimal.valueOf(500));

    // When / Then
    assertNoNotificationForCriteria(listing, criteria);
  }

  @Test
  void should_not_match_when_rooms_differ() {
    // Given — listing has 2 rooms, criteria requires 3
    var listing = buildFullyMatchingListing();
    var criteria = withRooms(fullyMatchingCriteria(), 3);

    // When / Then
    assertNoNotificationForCriteria(listing, criteria);
  }

  @Test
  void should_not_match_when_query_does_not_match_title_description_or_address() {
    // Given — query text is not found in title, description or address
    var listing = buildFullyMatchingListing();
    var criteria = withQuery(fullyMatchingCriteria(), "совершенно другой текст");

    // When / Then
    assertNoNotificationForCriteria(listing, criteria);
  }

  @Test
  void should_match_when_owner_only_true_and_listing_is_owner() {
    // Given
    var listing = buildFullyMatchingListing();
    listing.setIsOwner(true);
    var subscription = buildSubscription(10L, Set.of(TriggerType.NEW_LISTING));
    var criteria = withOwnerOnly(fullyMatchingCriteria(), true);
    var change = new ListingChange(listing, ListingChangeType.NEW, null, null, null);
    when(subscriptionRepository.findByActiveTrue()).thenReturn(List.of(subscription));
    when(searchCriteriaJsonMapper.toCriteria(subscription.getSearchCriteria())).thenReturn(criteria);

    // When
    notificationTriggerService.evaluate(List.of(change));

    // Then
    verify(notificationCreator).create(subscription, listing, TriggerType.NEW_LISTING);
  }

  @Test
  void should_not_match_when_owner_only_true_and_listing_is_not_owner() {
    // Given
    var listing = buildFullyMatchingListing();
    listing.setIsOwner(false);
    var criteria = withOwnerOnly(fullyMatchingCriteria(), true);

    // When / Then
    assertNoNotificationForCriteria(listing, criteria);
  }

  @Test
  void should_match_when_owner_only_true_and_listing_owner_flag_is_unknown() {
    // Given — isOwner is null (unknown), ownerOnly filter is lenient about unknown ownership
    var listing = buildFullyMatchingListing();
    listing.setIsOwner(null);
    var subscription = buildSubscription(10L, Set.of(TriggerType.NEW_LISTING));
    var criteria = withOwnerOnly(fullyMatchingCriteria(), true);
    var change = new ListingChange(listing, ListingChangeType.NEW, null, null, null);
    when(subscriptionRepository.findByActiveTrue()).thenReturn(List.of(subscription));
    when(searchCriteriaJsonMapper.toCriteria(subscription.getSearchCriteria())).thenReturn(criteria);

    // When
    notificationTriggerService.evaluate(List.of(change));

    // Then
    verify(notificationCreator).create(subscription, listing, TriggerType.NEW_LISTING);
  }

  @Test
  void should_match_when_owner_only_false_regardless_of_listing_owner_flag() {
    // Given — ownerOnly filter is off, listing is not from an owner
    var listing = buildFullyMatchingListing();
    listing.setIsOwner(false);
    var subscription = buildSubscription(10L, Set.of(TriggerType.NEW_LISTING));
    var criteria = withOwnerOnly(fullyMatchingCriteria(), false);
    var change = new ListingChange(listing, ListingChangeType.NEW, null, null, null);
    when(subscriptionRepository.findByActiveTrue()).thenReturn(List.of(subscription));
    when(searchCriteriaJsonMapper.toCriteria(subscription.getSearchCriteria())).thenReturn(criteria);

    // When
    notificationTriggerService.evaluate(List.of(change));

    // Then
    verify(notificationCreator).create(subscription, listing, TriggerType.NEW_LISTING);
  }

  // -------------------------------------------------------------------------
  // Blacklist exclusion (issue #414)
  // -------------------------------------------------------------------------

  @Test
  void should_not_create_notification_when_listing_is_blacklisted() {
    // Given
    var listing = buildFullyMatchingListing();
    var subscription = buildSubscription(10L, Set.of(TriggerType.NEW_LISTING));
    var change = new ListingChange(listing, ListingChangeType.NEW, null, null, null);
    when(subscriptionRepository.findByActiveTrue()).thenReturn(List.of(subscription));
    when(searchCriteriaJsonMapper.toCriteria(subscription.getSearchCriteria())).thenReturn(fullyMatchingCriteria());
    when(blacklistEntryRepository.findByUserIn(any()))
        .thenReturn(List.of(buildBlacklistEntry(subscription.getUser(), BlacklistEntryType.LISTING, "1")));

    // When
    notificationTriggerService.evaluate(List.of(change));

    // Then
    verify(notificationCreator, never()).create(any(), any(), any());
  }

  @Test
  void should_not_create_notification_when_source_is_blacklisted() {
    // Given
    var listing = buildFullyMatchingListing();
    var subscription = buildSubscription(10L, Set.of(TriggerType.NEW_LISTING));
    var change = new ListingChange(listing, ListingChangeType.NEW, null, null, null);
    when(subscriptionRepository.findByActiveTrue()).thenReturn(List.of(subscription));
    when(searchCriteriaJsonMapper.toCriteria(subscription.getSearchCriteria())).thenReturn(fullyMatchingCriteria());
    when(blacklistEntryRepository.findByUserIn(any()))
        .thenReturn(List.of(buildBlacklistEntry(subscription.getUser(), BlacklistEntryType.SOURCE, "onliner")));

    // When
    notificationTriggerService.evaluate(List.of(change));

    // Then
    verify(notificationCreator, never()).create(any(), any(), any());
  }

  @Test
  void should_not_create_notification_when_keyword_matches_title() {
    // Given — buildFullyMatchingListing's title is "Уютная квартира в центре"
    var listing = buildFullyMatchingListing();
    var subscription = buildSubscription(10L, Set.of(TriggerType.NEW_LISTING));
    var change = new ListingChange(listing, ListingChangeType.NEW, null, null, null);
    when(subscriptionRepository.findByActiveTrue()).thenReturn(List.of(subscription));
    when(searchCriteriaJsonMapper.toCriteria(subscription.getSearchCriteria())).thenReturn(fullyMatchingCriteria());
    when(blacklistEntryRepository.findByUserIn(any()))
        .thenReturn(List.of(buildBlacklistEntry(subscription.getUser(), BlacklistEntryType.KEYWORD, "уютная")));

    // When
    notificationTriggerService.evaluate(List.of(change));

    // Then
    verify(notificationCreator, never()).create(any(), any(), any());
  }

  @Test
  void should_create_notification_when_blacklist_entry_belongs_to_a_different_user() {
    // Given — the blacklist entry matches this listing but belongs to a different user
    var listing = buildFullyMatchingListing();
    var subscription = buildSubscription(10L, Set.of(TriggerType.NEW_LISTING));
    var otherUser = new User();
    otherUser.setId(999L);
    var change = new ListingChange(listing, ListingChangeType.NEW, null, null, null);
    when(subscriptionRepository.findByActiveTrue()).thenReturn(List.of(subscription));
    when(searchCriteriaJsonMapper.toCriteria(subscription.getSearchCriteria())).thenReturn(fullyMatchingCriteria());
    when(blacklistEntryRepository.findByUserIn(any()))
        .thenReturn(List.of(buildBlacklistEntry(otherUser, BlacklistEntryType.LISTING, "1")));

    // When
    notificationTriggerService.evaluate(List.of(change));

    // Then
    verify(notificationCreator).create(subscription, listing, TriggerType.NEW_LISTING);
  }

  @Test
  void should_load_blacklists_in_one_batched_query_regardless_of_subscription_count() {
    // Given — two distinct subscribers, neither blacklisted
    var listing = buildFullyMatchingListing();
    var subscriptionA = buildSubscription(10L, Set.of(TriggerType.NEW_LISTING));
    var subscriptionB = buildSubscription(20L, Set.of(TriggerType.NEW_LISTING));
    var change = new ListingChange(listing, ListingChangeType.NEW, null, null, null);
    when(subscriptionRepository.findByActiveTrue()).thenReturn(List.of(subscriptionA, subscriptionB));
    when(searchCriteriaJsonMapper.toCriteria(any())).thenReturn(fullyMatchingCriteria());

    // When
    notificationTriggerService.evaluate(List.of(change));

    // Then — one findByUserIn call for the whole run, not one per subscription
    verify(blacklistEntryRepository, times(1)).findByUserIn(any());
    verify(notificationCreator).create(subscriptionA, listing, TriggerType.NEW_LISTING);
    verify(notificationCreator).create(subscriptionB, listing, TriggerType.NEW_LISTING);
  }

  // -------------------------------------------------------------------------
  // helpers
  // -------------------------------------------------------------------------

  private void assertNoNotificationForCriteria(Listing listing, SubscriptionSearchCriteria criteria) {
    var subscription = buildSubscription(10L, Set.of(TriggerType.NEW_LISTING));
    var change = new ListingChange(listing, ListingChangeType.NEW, null, null, null);
    when(subscriptionRepository.findByActiveTrue()).thenReturn(List.of(subscription));
    when(searchCriteriaJsonMapper.toCriteria(subscription.getSearchCriteria())).thenReturn(criteria);

    notificationTriggerService.evaluate(List.of(change));

    verify(notificationCreator, never()).create(any(), any(), any());
  }

  private Listing buildListing(Long id, ListingStatus status, DealType dealType, BigDecimal price, String city) {
    var source = new Source();
    source.setCode("onliner");
    var listing = new Listing();
    listing.setId(id);
    listing.setStatus(status);
    listing.setDealType(dealType);
    listing.setPropertyType("APARTMENT");
    listing.setPrice(price);
    listing.setSource(source);
    listing.setCity(city);
    listing.setTitle("Test listing");
    return listing;
  }

  private Listing buildFullyMatchingListing() {
    var listing = buildListing(1L, ListingStatus.ACTIVE, DealType.RENT, BigDecimal.valueOf(1000), "Минск");
    listing.setRooms(2);
    listing.setTitle("Уютная квартира в центре");
    listing.setDescription("Просторная квартира с ремонтом");
    listing.setAddress("ул. Ленина, 1");
    return listing;
  }

  private SubscriptionSearchCriteria fullyMatchingCriteria() {
    return new SubscriptionSearchCriteria(
        DealType.RENT, "APARTMENT", "onliner", "Минск", null,
        BigDecimal.valueOf(500), BigDecimal.valueOf(1500), 2, "квартира", null);
  }

  private SubscriptionSearchCriteria withPropertyType(SubscriptionSearchCriteria criteria, String propertyType) {
    return new SubscriptionSearchCriteria(criteria.dealType(), propertyType, criteria.sourceId(), criteria.city(),
        criteria.cityId(), criteria.priceMin(), criteria.priceMax(), criteria.rooms(), criteria.query(),
        criteria.ownerOnly());
  }

  private SubscriptionSearchCriteria withSourceId(SubscriptionSearchCriteria criteria, String sourceId) {
    return new SubscriptionSearchCriteria(criteria.dealType(), criteria.propertyType(), sourceId, criteria.city(),
        criteria.cityId(), criteria.priceMin(), criteria.priceMax(), criteria.rooms(), criteria.query(),
        criteria.ownerOnly());
  }

  private SubscriptionSearchCriteria withCity(SubscriptionSearchCriteria criteria, String city) {
    return new SubscriptionSearchCriteria(criteria.dealType(), criteria.propertyType(), criteria.sourceId(), city,
        criteria.cityId(), criteria.priceMin(), criteria.priceMax(), criteria.rooms(), criteria.query(),
        criteria.ownerOnly());
  }

  private SubscriptionSearchCriteria withCityId(SubscriptionSearchCriteria criteria, Long cityId) {
    return new SubscriptionSearchCriteria(criteria.dealType(), criteria.propertyType(), criteria.sourceId(),
        criteria.city(), cityId, criteria.priceMin(), criteria.priceMax(), criteria.rooms(), criteria.query(),
        criteria.ownerOnly());
  }

  private SubscriptionSearchCriteria withPriceMin(SubscriptionSearchCriteria criteria, BigDecimal priceMin) {
    return new SubscriptionSearchCriteria(criteria.dealType(), criteria.propertyType(), criteria.sourceId(),
        criteria.city(), criteria.cityId(), priceMin, criteria.priceMax(), criteria.rooms(), criteria.query(),
        criteria.ownerOnly());
  }

  private SubscriptionSearchCriteria withPriceMax(SubscriptionSearchCriteria criteria, BigDecimal priceMax) {
    return new SubscriptionSearchCriteria(criteria.dealType(), criteria.propertyType(), criteria.sourceId(),
        criteria.city(), criteria.cityId(), criteria.priceMin(), priceMax, criteria.rooms(), criteria.query(),
        criteria.ownerOnly());
  }

  private SubscriptionSearchCriteria withRooms(SubscriptionSearchCriteria criteria, Integer rooms) {
    return new SubscriptionSearchCriteria(criteria.dealType(), criteria.propertyType(), criteria.sourceId(),
        criteria.city(), criteria.cityId(), criteria.priceMin(), criteria.priceMax(), rooms, criteria.query(),
        criteria.ownerOnly());
  }

  private SubscriptionSearchCriteria withQuery(SubscriptionSearchCriteria criteria, String query) {
    return new SubscriptionSearchCriteria(criteria.dealType(), criteria.propertyType(), criteria.sourceId(),
        criteria.city(), criteria.cityId(), criteria.priceMin(), criteria.priceMax(), criteria.rooms(), query,
        criteria.ownerOnly());
  }

  private SubscriptionSearchCriteria withOwnerOnly(SubscriptionSearchCriteria criteria, Boolean ownerOnly) {
    return new SubscriptionSearchCriteria(criteria.dealType(), criteria.propertyType(), criteria.sourceId(),
        criteria.city(), criteria.cityId(), criteria.priceMin(), criteria.priceMax(), criteria.rooms(),
        criteria.query(), ownerOnly);
  }

  private BlacklistEntry buildBlacklistEntry(User user, BlacklistEntryType type, String value) {
    var entry = new BlacklistEntry();
    entry.setUser(user);
    entry.setType(type);
    entry.setValue(value);
    return entry;
  }

  private Subscription buildSubscription(Long id, Set<TriggerType> triggers) {
    var user = new User();
    user.setId(id);
    var subscription = new Subscription();
    subscription.setId(id);
    subscription.setUser(user);
    subscription.setActive(true);
    subscription.setTriggers(triggers);
    subscription.setSearchCriteria(Map.of("id", id));
    return subscription;
  }

  private SubscriptionSearchCriteria matchAllCriteria() {
    return new SubscriptionSearchCriteria(null, null, null, null, null, null, null, null, null, null);
  }
}
