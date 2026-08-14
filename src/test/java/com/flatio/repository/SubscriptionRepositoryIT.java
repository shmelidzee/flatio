package com.flatio.repository;

import com.flatio.domain.subscription.DeliveryMode;
import com.flatio.domain.subscription.Subscription;
import com.flatio.domain.subscription.SubscriptionChannelType;
import com.flatio.domain.subscription.TriggerType;
import com.flatio.domain.user.User;
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
class SubscriptionRepositoryIT {

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
  private SubscriptionRepository subscriptionRepository;

  @Autowired
  private UserRepository userRepository;

  @BeforeEach
  void setUp() {
    subscriptionRepository.deleteAll();
    userRepository.deleteAll();
  }

  // -------------------------------------------------------------------------
  // Persistence round-trip
  // -------------------------------------------------------------------------

  @Test
  void should_persist_and_reload_search_criteria_as_json() {
    // Given
    var user = userRepository.save(buildUser("Pavel"));
    var subscription = buildSubscription(user, "2-комнатные", Map.of("city", "Минск", "priceMax", 1500));
    var saved = subscriptionRepository.saveAndFlush(subscription);

    // When
    var reloaded = subscriptionRepository.findById(saved.getId()).orElseThrow();

    // Then
    assertThat(reloaded.getSearchCriteria()).containsEntry("city", "Минск");
    assertThat(reloaded.getName()).isEqualTo("2-комнатные");
    assertThat(reloaded.getCreatedAt()).isNotNull();
    assertThat(reloaded.getUpdatedAt()).isNotNull();
  }

  @Test
  void should_persist_triggers_as_element_collection() {
    // Given
    var user = userRepository.save(buildUser("Pavel"));
    var subscription = buildSubscription(user, "Filter", Map.of());
    subscription.setTriggers(Set.of(TriggerType.NEW_LISTING, TriggerType.PRICE_DROP));
    var saved = subscriptionRepository.saveAndFlush(subscription);

    // When
    var reloaded = subscriptionRepository.findById(saved.getId()).orElseThrow();

    // Then
    assertThat(reloaded.getTriggers()).containsExactlyInAnyOrder(TriggerType.NEW_LISTING, TriggerType.PRICE_DROP);
  }

  // -------------------------------------------------------------------------
  // findByUser
  // -------------------------------------------------------------------------

  @Test
  void should_find_subscriptions_by_user_paginated() {
    // Given
    var user = userRepository.save(buildUser("Pavel"));
    var otherUser = userRepository.save(buildUser("Anna"));
    subscriptionRepository.save(buildSubscription(user, "Filter 1", Map.of()));
    subscriptionRepository.save(buildSubscription(otherUser, "Filter 2", Map.of()));

    // When
    var result = subscriptionRepository.findByUser(user, PageRequest.of(0, 20));

    // Then
    assertThat(result.getContent()).hasSize(1);
    assertThat(result.getContent().get(0).getName()).isEqualTo("Filter 1");
  }

  // -------------------------------------------------------------------------
  // findByIdAndUser
  // -------------------------------------------------------------------------

  @Test
  void should_find_subscription_when_id_and_user_match() {
    // Given
    var user = userRepository.save(buildUser("Pavel"));
    var saved = subscriptionRepository.save(buildSubscription(user, "Filter", Map.of()));

    // When
    var result = subscriptionRepository.findByIdAndUser(saved.getId(), user);

    // Then
    assertThat(result).isPresent();
  }

  @Test
  void should_return_empty_when_subscription_belongs_to_another_user() {
    // Given
    var owner = userRepository.save(buildUser("Pavel"));
    var stranger = userRepository.save(buildUser("Anna"));
    var saved = subscriptionRepository.save(buildSubscription(owner, "Filter", Map.of()));

    // When — stranger tries to access owner's subscription
    var result = subscriptionRepository.findByIdAndUser(saved.getId(), stranger);

    // Then
    assertThat(result).isEmpty();
  }

  // -------------------------------------------------------------------------
  // countByUserAndActiveTrue
  // -------------------------------------------------------------------------

  @Test
  void should_count_only_active_subscriptions_for_user() {
    // Given
    var user = userRepository.save(buildUser("Pavel"));
    var active = buildSubscription(user, "Active", Map.of());
    var paused = buildSubscription(user, "Paused", Map.of());
    paused.setActive(false);
    subscriptionRepository.save(active);
    subscriptionRepository.save(paused);

    // When
    var count = subscriptionRepository.countByUserAndActiveTrue(user);

    // Then
    assertThat(count).isEqualTo(1);
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

  private Subscription buildSubscription(User user, String name, Map<String, Object> searchCriteria) {
    var subscription = new Subscription();
    subscription.setUser(user);
    subscription.setName(name);
    subscription.setSearchCriteria(searchCriteria);
    subscription.setDeliveryMode(DeliveryMode.REALTIME);
    subscription.setChannelType(SubscriptionChannelType.TELEGRAM);
    subscription.setTriggers(Set.of(TriggerType.NEW_LISTING));
    return subscription;
  }
}
