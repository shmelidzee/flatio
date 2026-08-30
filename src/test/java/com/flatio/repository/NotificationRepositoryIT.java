package com.flatio.repository;

import com.flatio.domain.listing.DealType;
import com.flatio.domain.listing.Listing;
import com.flatio.domain.listing.ListingStatus;
import com.flatio.domain.notification.Notification;
import com.flatio.domain.notification.NotificationStatus;
import com.flatio.domain.subscription.DeliveryMode;
import com.flatio.domain.subscription.Subscription;
import com.flatio.domain.subscription.SubscriptionChannelType;
import com.flatio.domain.subscription.TriggerType;
import com.flatio.domain.user.User;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
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

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
class NotificationRepositoryIT {

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
  private NotificationRepository notificationRepository;

  @Autowired
  private SubscriptionRepository subscriptionRepository;

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

  private Instant retryBefore;

  @BeforeEach
  void setUp() {
    notificationRepository.deleteAll();
    subscriptionRepository.deleteAll();
    listingRepository.deleteAll();
    userRepository.deleteAll();
    retryBefore = Instant.now().minusSeconds(60);
  }

  // -------------------------------------------------------------------------
  // findSendable
  // -------------------------------------------------------------------------

  @Test
  void should_return_pending_notification_when_user_is_active() {
    // Given
    var user = buildUser("Pavel", true);
    var subscription = buildSubscription(user);
    var listing = buildListing("ext-notif-1");
    saveNotification(subscription, listing, NotificationStatus.PENDING);

    // When
    var result = notificationRepository.findSendable(DeliveryMode.REALTIME,
        NotificationStatus.PENDING, NotificationStatus.FAILED, retryBefore, PageRequest.of(0, 10));

    // Then
    assertThat(result).hasSize(1);
  }

  @Test
  void should_exclude_notification_when_subscription_user_is_deactivated() {
    // Given — issue #438: a deactivated user's pending notifications must not be delivered
    var user = buildUser("Anna", false);
    var subscription = buildSubscription(user);
    var listing = buildListing("ext-notif-2");
    saveNotification(subscription, listing, NotificationStatus.PENDING);

    // When
    var result = notificationRepository.findSendable(DeliveryMode.REALTIME,
        NotificationStatus.PENDING, NotificationStatus.FAILED, retryBefore, PageRequest.of(0, 10));

    // Then
    assertThat(result).isEmpty();
  }

  @Test
  void should_not_let_deactivated_user_backlog_starve_active_user_notification() {
    // Given — a deactivated user's notification must not occupy the active user's batch slot
    var deactivatedUser = buildUser("Deactivated", false);
    var deactivatedSubscription = buildSubscription(deactivatedUser);
    saveNotification(deactivatedSubscription, buildListing("ext-notif-3"), NotificationStatus.PENDING);

    var activeUser = buildUser("Active", true);
    var activeSubscription = buildSubscription(activeUser);
    saveNotification(activeSubscription, buildListing("ext-notif-4"), NotificationStatus.PENDING);

    // When
    var result = notificationRepository.findSendable(DeliveryMode.REALTIME,
        NotificationStatus.PENDING, NotificationStatus.FAILED, retryBefore, PageRequest.of(0, 1));

    // Then
    assertThat(result).hasSize(1);
    assertThat(result.get(0).getSubscription().getUser().getDisplayName()).isEqualTo("Active");
  }

  // -------------------------------------------------------------------------
  // findPendingForDigest (issue #410)
  // -------------------------------------------------------------------------

  @Test
  void should_return_digest_notification_when_subscription_delivery_mode_is_digest() {
    // Given
    var user = buildUser("Pavel", true);
    var subscription = buildSubscription(user, DeliveryMode.DIGEST);
    saveNotification(subscription, buildListing("ext-digest-1"), NotificationStatus.PENDING);

    // When
    var result = notificationRepository.findPendingForDigest(DeliveryMode.DIGEST, DeliveryMode.REALTIME,
        NotificationStatus.PENDING, Instant.now(), PageRequest.of(0, 10));

    // Then
    assertThat(result).hasSize(1);
  }

  @Test
  void should_exclude_recent_realtime_notification_from_digest() {
    // Given — a fresh REALTIME notification is still within its normal delivery window
    var user = buildUser("Pavel", true);
    var subscription = buildSubscription(user, DeliveryMode.REALTIME);
    saveNotification(subscription, buildListing("ext-digest-2"), NotificationStatus.PENDING);
    var overflowBefore = Instant.now().minusSeconds(3600);

    // When
    var result = notificationRepository.findPendingForDigest(DeliveryMode.DIGEST, DeliveryMode.REALTIME,
        NotificationStatus.PENDING, overflowBefore, PageRequest.of(0, 10));

    // Then
    assertThat(result).isEmpty();
  }

  @Test
  void should_include_overflowed_realtime_notification_in_digest() {
    // Given — FR-SUB-9 forced fallback: a REALTIME notification old enough to have overflowed
    var user = buildUser("Pavel", true);
    var subscription = buildSubscription(user, DeliveryMode.REALTIME);
    saveNotification(subscription, buildListing("ext-digest-3"), NotificationStatus.PENDING);
    var overflowBefore = Instant.now().plusSeconds(3600);

    // When
    var result = notificationRepository.findPendingForDigest(DeliveryMode.DIGEST, DeliveryMode.REALTIME,
        NotificationStatus.PENDING, overflowBefore, PageRequest.of(0, 10));

    // Then
    assertThat(result).hasSize(1);
  }

  // -------------------------------------------------------------------------
  // findPendingByDeliveryMode (issue #410)
  // -------------------------------------------------------------------------

  @Test
  void should_return_daily_notification_when_subscription_delivery_mode_is_daily() {
    // Given
    var user = buildUser("Pavel", true);
    var subscription = buildSubscription(user, DeliveryMode.DAILY);
    saveNotification(subscription, buildListing("ext-daily-1"), NotificationStatus.PENDING);

    // When
    var result = notificationRepository.findPendingByDeliveryMode(
        DeliveryMode.DAILY, NotificationStatus.PENDING, PageRequest.of(0, 10));

    // Then
    assertThat(result).hasSize(1);
  }

  @Test
  void should_exclude_realtime_notification_from_daily_batch() {
    // Given
    var user = buildUser("Pavel", true);
    var subscription = buildSubscription(user, DeliveryMode.REALTIME);
    saveNotification(subscription, buildListing("ext-daily-2"), NotificationStatus.PENDING);

    // When
    var result = notificationRepository.findPendingByDeliveryMode(
        DeliveryMode.DAILY, NotificationStatus.PENDING, PageRequest.of(0, 10));

    // Then
    assertThat(result).isEmpty();
  }

  // -------------------------------------------------------------------------
  // helpers
  // -------------------------------------------------------------------------

  private void saveNotification(Subscription subscription, Listing listing, NotificationStatus status) {
    var notification = new Notification();
    notification.setSubscription(subscription);
    notification.setListing(listing);
    notification.setTriggerType(TriggerType.NEW_LISTING);
    notification.setStatus(status);
    notificationRepository.saveAndFlush(notification);
  }

  private User buildUser(String displayName, boolean active) {
    var user = new User();
    user.setDisplayName(displayName);
    user.setActive(active);
    return userRepository.save(user);
  }

  private Subscription buildSubscription(User user) {
    return buildSubscription(user, DeliveryMode.REALTIME);
  }

  private Subscription buildSubscription(User user, DeliveryMode deliveryMode) {
    var subscription = new Subscription();
    subscription.setUser(user);
    subscription.setName("Filter");
    subscription.setSearchCriteria(Map.of());
    subscription.setDeliveryMode(deliveryMode);
    subscription.setChannelType(SubscriptionChannelType.TELEGRAM);
    subscription.setTriggers(Set.of(TriggerType.NEW_LISTING));
    return subscriptionRepository.save(subscription);
  }

  private Listing buildListing(String externalId) {
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
    return listingRepository.save(listing);
  }
}
