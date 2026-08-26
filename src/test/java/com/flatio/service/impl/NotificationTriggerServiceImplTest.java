package com.flatio.service.impl;

import com.flatio.domain.listing.DealType;
import com.flatio.domain.listing.Listing;
import com.flatio.domain.listing.ListingStatus;
import com.flatio.domain.notification.Notification;
import com.flatio.domain.notification.NotificationStatus;
import com.flatio.domain.source.Source;
import com.flatio.domain.subscription.Subscription;
import com.flatio.domain.subscription.TriggerType;
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
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
  private SearchCriteriaJsonMapper searchCriteriaJsonMapper;

  private NotificationTriggerServiceImpl notificationTriggerService;

  @BeforeEach
  void setUp() {
    notificationTriggerService = new NotificationTriggerServiceImpl(
        subscriptionRepository, notificationRepository, cityRepository, searchCriteriaJsonMapper
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
    when(notificationRepository.findBySubscriptionAndListingAndTriggerType(subscription, listing, TriggerType.NEW_LISTING))
        .thenReturn(Optional.empty());

    // When
    notificationTriggerService.evaluate(List.of(change));

    // Then
    var captor = ArgumentCaptor.forClass(Notification.class);
    verify(notificationRepository).save(captor.capture());
    Notification saved = captor.getValue();
    assertThat(saved.getSubscription()).isEqualTo(subscription);
    assertThat(saved.getListing()).isEqualTo(listing);
    assertThat(saved.getTriggerType()).isEqualTo(TriggerType.NEW_LISTING);
    assertThat(saved.getStatus()).isEqualTo(NotificationStatus.PENDING);
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
    verify(notificationRepository, never()).save(any());
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
    verify(notificationRepository, never()).save(any());
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
    verify(notificationRepository, never()).save(any());
  }

  @Test
  void should_do_nothing_when_changes_list_is_empty() {
    // Given — no listing changes to evaluate

    // When
    notificationTriggerService.evaluate(List.of());

    // Then
    verify(subscriptionRepository, never()).findByActiveTrue();
    verify(notificationRepository, never()).save(any());
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
    when(notificationRepository.findBySubscriptionAndListingAndTriggerType(subscription, listing, TriggerType.PRICE_DROP))
        .thenReturn(Optional.empty());

    // When
    notificationTriggerService.evaluate(List.of(change));

    // Then
    var captor = ArgumentCaptor.forClass(Notification.class);
    verify(notificationRepository).save(captor.capture());
    assertThat(captor.getValue().getTriggerType()).isEqualTo(TriggerType.PRICE_DROP);
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
    verify(notificationRepository, never()).save(any());
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
    when(notificationRepository.findBySubscriptionAndListingAndTriggerType(subscription, listing, TriggerType.REPOSTED))
        .thenReturn(Optional.empty());

    // When
    notificationTriggerService.evaluate(List.of(change));

    // Then — only one notification is created, and it is REPOSTED
    var captor = ArgumentCaptor.forClass(Notification.class);
    verify(notificationRepository, times(1)).save(captor.capture());
    assertThat(captor.getValue().getTriggerType()).isEqualTo(TriggerType.REPOSTED);
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
    when(notificationRepository.findBySubscriptionAndListingAndTriggerType(subscription, listing, TriggerType.REACTIVATED))
        .thenReturn(Optional.empty());

    // When
    notificationTriggerService.evaluate(List.of(change));

    // Then
    var captor = ArgumentCaptor.forClass(Notification.class);
    verify(notificationRepository).save(captor.capture());
    assertThat(captor.getValue().getTriggerType()).isEqualTo(TriggerType.REACTIVATED);
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
    when(subscriptionRepository.findByActiveTrue()).thenReturn(List.of(subscription));
    when(searchCriteriaJsonMapper.toCriteria(subscription.getSearchCriteria())).thenReturn(matchAllCriteria());
    when(notificationRepository.findBySubscriptionAndListingAndTriggerType(subscription, listing, TriggerType.NEW_LISTING))
        .thenReturn(Optional.of(existingNotification));

    // When
    notificationTriggerService.evaluate(List.of(change));

    // Then
    verify(notificationRepository, never()).save(any());
  }

  // -------------------------------------------------------------------------
  // helpers
  // -------------------------------------------------------------------------

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

  private Subscription buildSubscription(Long id, Set<TriggerType> triggers) {
    var subscription = new Subscription();
    subscription.setId(id);
    subscription.setActive(true);
    subscription.setTriggers(triggers);
    subscription.setSearchCriteria(Map.of("id", id));
    return subscription;
  }

  private SubscriptionSearchCriteria matchAllCriteria() {
    return new SubscriptionSearchCriteria(null, null, null, null, null, null, null, null, null, null);
  }
}
