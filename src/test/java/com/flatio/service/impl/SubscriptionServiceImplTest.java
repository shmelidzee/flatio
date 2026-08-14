package com.flatio.service.impl;

import com.flatio.common.exception.SubscriptionLimitExceededException;
import com.flatio.common.exception.SubscriptionNotFoundException;
import com.flatio.config.SubscriptionLimitProperties;
import com.flatio.domain.subscription.DeliveryMode;
import com.flatio.domain.subscription.Subscription;
import com.flatio.domain.subscription.SubscriptionChannelType;
import com.flatio.domain.subscription.TriggerType;
import com.flatio.domain.user.User;
import com.flatio.domain.user.UserRole;
import com.flatio.repository.SubscriptionRepository;
import com.flatio.repository.UserRepository;
import com.flatio.web.dto.CreateSubscriptionRequest;
import com.flatio.web.dto.SubscriptionResponse;
import com.flatio.web.dto.SubscriptionSearchCriteria;
import com.flatio.web.dto.UpdateSubscriptionRequest;
import com.flatio.web.mapper.SubscriptionMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SubscriptionServiceImplTest {

  @Mock
  private SubscriptionRepository subscriptionRepository;

  @Mock
  private UserRepository userRepository;

  @Mock
  private SubscriptionMapper subscriptionMapper;

  private SubscriptionServiceImpl subscriptionService;

  @BeforeEach
  void setUp() {
    var limitProperties = new SubscriptionLimitProperties(3, 20);
    subscriptionService = new SubscriptionServiceImpl(
        subscriptionRepository, userRepository, subscriptionMapper, limitProperties
    );
  }

  // -------------------------------------------------------------------------
  // create
  // -------------------------------------------------------------------------

  @Test
  void should_create_active_subscription_when_under_limit() {
    // Given
    var user = buildUser(1L, UserRole.USER);
    var request = buildCreateRequest(null);
    var entity = new Subscription();
    var response = mock(SubscriptionResponse.class);
    when(userRepository.findById(1L)).thenReturn(Optional.of(user));
    when(subscriptionRepository.countByUserAndActiveTrue(user)).thenReturn(2L);
    when(subscriptionMapper.toEntity(request)).thenReturn(entity);
    when(subscriptionRepository.save(entity)).thenReturn(entity);
    when(subscriptionMapper.toResponse(entity)).thenReturn(response);

    // When
    var result = subscriptionService.create(1L, request);

    // Then
    assertThat(result).isSameAs(response);
    assertThat(entity.getUser()).isEqualTo(user);
    assertThat(entity.isActive()).isTrue();
  }

  @Test
  void should_default_price_drop_threshold_when_not_provided() {
    // Given
    var user = buildUser(1L, UserRole.USER);
    var request = buildCreateRequest(null);
    var entity = new Subscription();
    when(userRepository.findById(1L)).thenReturn(Optional.of(user));
    when(subscriptionRepository.countByUserAndActiveTrue(user)).thenReturn(0L);
    when(subscriptionMapper.toEntity(request)).thenReturn(entity);
    when(subscriptionRepository.save(entity)).thenReturn(entity);
    when(subscriptionMapper.toResponse(entity)).thenReturn(mock(SubscriptionResponse.class));

    // When
    subscriptionService.create(1L, request);

    // Then
    assertThat(entity.getPriceDropThreshold()).isEqualByComparingTo(new BigDecimal("5.00"));
  }

  @Test
  void should_keep_requested_price_drop_threshold_when_provided() {
    // Given
    var user = buildUser(1L, UserRole.USER);
    var request = buildCreateRequest(new BigDecimal("10.00"));
    var entity = new Subscription();
    when(userRepository.findById(1L)).thenReturn(Optional.of(user));
    when(subscriptionRepository.countByUserAndActiveTrue(user)).thenReturn(0L);
    when(subscriptionMapper.toEntity(request)).thenReturn(entity);
    when(subscriptionRepository.save(entity)).thenReturn(entity);
    when(subscriptionMapper.toResponse(entity)).thenReturn(mock(SubscriptionResponse.class));

    // When
    subscriptionService.create(1L, request);

    // Then
    assertThat(entity.getPriceDropThreshold()).isEqualByComparingTo(new BigDecimal("10.00"));
  }

  @Test
  void should_throw_when_user_role_limit_reached_on_create() {
    // Given — USER tariff limit is 3, already at 3 active subscriptions
    var user = buildUser(1L, UserRole.USER);
    var request = buildCreateRequest(null);
    when(userRepository.findById(1L)).thenReturn(Optional.of(user));
    when(subscriptionRepository.countByUserAndActiveTrue(user)).thenReturn(3L);

    // When / Then
    assertThatThrownBy(() -> subscriptionService.create(1L, request))
        .isInstanceOf(SubscriptionLimitExceededException.class)
        .hasMessageContaining("3");
    verify(subscriptionRepository, never()).save(any());
  }

  @Test
  void should_use_pro_limit_when_user_role_is_pro() {
    // Given — PRO tariff limit is 20, at 20 active subscriptions
    var user = buildUser(1L, UserRole.PRO);
    var request = buildCreateRequest(null);
    when(userRepository.findById(1L)).thenReturn(Optional.of(user));
    when(subscriptionRepository.countByUserAndActiveTrue(user)).thenReturn(20L);

    // When / Then
    assertThatThrownBy(() -> subscriptionService.create(1L, request))
        .isInstanceOf(SubscriptionLimitExceededException.class)
        .hasMessageContaining("20");
  }

  @Test
  void should_throw_when_authenticated_user_not_found_on_create() {
    // Given
    when(userRepository.findById(99L)).thenReturn(Optional.empty());

    // When / Then
    assertThatThrownBy(() -> subscriptionService.create(99L, buildCreateRequest(null)))
        .isInstanceOf(IllegalStateException.class);
  }

  // -------------------------------------------------------------------------
  // findByUser
  // -------------------------------------------------------------------------

  @Test
  void should_return_page_of_subscriptions_for_user() {
    // Given
    var user = buildUser(1L, UserRole.USER);
    var subscription = new Subscription();
    var response = mock(SubscriptionResponse.class);
    var pageable = PageRequest.of(0, 20);
    Page<Subscription> page = new PageImpl<>(List.of(subscription), pageable, 1);
    when(userRepository.findById(1L)).thenReturn(Optional.of(user));
    when(subscriptionRepository.findByUser(user, pageable)).thenReturn(page);
    when(subscriptionMapper.toResponse(subscription)).thenReturn(response);

    // When
    var result = subscriptionService.findByUser(1L, pageable);

    // Then
    assertThat(result.getContent()).containsExactly(response);
  }

  // -------------------------------------------------------------------------
  // findByIdForUser
  // -------------------------------------------------------------------------

  @Test
  void should_return_subscription_when_owned_by_user() {
    // Given
    var user = buildUser(1L, UserRole.USER);
    var entity = new Subscription();
    var response = mock(SubscriptionResponse.class);
    when(userRepository.findById(1L)).thenReturn(Optional.of(user));
    when(subscriptionRepository.findByIdAndUser(5L, user)).thenReturn(Optional.of(entity));
    when(subscriptionMapper.toResponse(entity)).thenReturn(response);

    // When
    var result = subscriptionService.findByIdForUser(1L, 5L);

    // Then
    assertThat(result).isSameAs(response);
  }

  @Test
  void should_throw_not_found_when_subscription_not_owned_by_user() {
    // Given
    var user = buildUser(1L, UserRole.USER);
    when(userRepository.findById(1L)).thenReturn(Optional.of(user));
    when(subscriptionRepository.findByIdAndUser(99L, user)).thenReturn(Optional.empty());

    // When / Then
    assertThatThrownBy(() -> subscriptionService.findByIdForUser(1L, 99L))
        .isInstanceOf(SubscriptionNotFoundException.class)
        .hasMessageContaining("99");
  }

  // -------------------------------------------------------------------------
  // update
  // -------------------------------------------------------------------------

  @Test
  void should_update_subscription_fields_when_owned_by_user() {
    // Given
    var user = buildUser(1L, UserRole.USER);
    var entity = new Subscription();
    var request = buildUpdateRequest(new BigDecimal("7.50"));
    var response = mock(SubscriptionResponse.class);
    when(userRepository.findById(1L)).thenReturn(Optional.of(user));
    when(subscriptionRepository.findByIdAndUser(5L, user)).thenReturn(Optional.of(entity));
    when(subscriptionRepository.save(entity)).thenReturn(entity);
    when(subscriptionMapper.toResponse(entity)).thenReturn(response);

    // When
    var result = subscriptionService.update(1L, 5L, request);

    // Then
    assertThat(result).isSameAs(response);
    assertThat(entity.getPriceDropThreshold()).isEqualByComparingTo(new BigDecimal("7.50"));
    verify(subscriptionMapper).updateEntity(request, entity);
  }

  @Test
  void should_throw_not_found_when_updating_subscription_not_owned_by_user() {
    // Given
    var user = buildUser(1L, UserRole.USER);
    when(userRepository.findById(1L)).thenReturn(Optional.of(user));
    when(subscriptionRepository.findByIdAndUser(99L, user)).thenReturn(Optional.empty());

    // When / Then
    assertThatThrownBy(() -> subscriptionService.update(1L, 99L, buildUpdateRequest(null)))
        .isInstanceOf(SubscriptionNotFoundException.class);
  }

  // -------------------------------------------------------------------------
  // pause
  // -------------------------------------------------------------------------

  @Test
  void should_deactivate_subscription_on_pause() {
    // Given
    var user = buildUser(1L, UserRole.USER);
    var entity = new Subscription();
    entity.setActive(true);
    var response = mock(SubscriptionResponse.class);
    when(userRepository.findById(1L)).thenReturn(Optional.of(user));
    when(subscriptionRepository.findByIdAndUser(5L, user)).thenReturn(Optional.of(entity));
    when(subscriptionRepository.save(entity)).thenReturn(entity);
    when(subscriptionMapper.toResponse(entity)).thenReturn(response);

    // When
    subscriptionService.pause(1L, 5L);

    // Then
    assertThat(entity.isActive()).isFalse();
  }

  // -------------------------------------------------------------------------
  // resume
  // -------------------------------------------------------------------------

  @Test
  void should_reactivate_paused_subscription_when_under_limit() {
    // Given
    var user = buildUser(1L, UserRole.USER);
    var entity = new Subscription();
    entity.setUser(user);
    entity.setActive(false);
    var response = mock(SubscriptionResponse.class);
    when(userRepository.findById(1L)).thenReturn(Optional.of(user));
    when(subscriptionRepository.findByIdAndUser(5L, user)).thenReturn(Optional.of(entity));
    when(subscriptionRepository.countByUserAndActiveTrue(user)).thenReturn(1L);
    when(subscriptionRepository.save(entity)).thenReturn(entity);
    when(subscriptionMapper.toResponse(entity)).thenReturn(response);

    // When
    subscriptionService.resume(1L, 5L);

    // Then
    assertThat(entity.isActive()).isTrue();
  }

  @Test
  void should_throw_when_resuming_would_exceed_limit() {
    // Given — user already has 3 active subscriptions (USER tariff limit)
    var user = buildUser(1L, UserRole.USER);
    var entity = new Subscription();
    entity.setUser(user);
    entity.setActive(false);
    when(userRepository.findById(1L)).thenReturn(Optional.of(user));
    when(subscriptionRepository.findByIdAndUser(5L, user)).thenReturn(Optional.of(entity));
    when(subscriptionRepository.countByUserAndActiveTrue(user)).thenReturn(3L);

    // When / Then
    assertThatThrownBy(() -> subscriptionService.resume(1L, 5L))
        .isInstanceOf(SubscriptionLimitExceededException.class);
    assertThat(entity.isActive()).isFalse();
    verify(subscriptionRepository, never()).save(any());
  }

  @Test
  void should_not_recheck_limit_when_resuming_already_active_subscription() {
    // Given — subscription is already active, resume is a no-op with respect to the limit
    var user = buildUser(1L, UserRole.USER);
    var entity = new Subscription();
    entity.setUser(user);
    entity.setActive(true);
    var response = mock(SubscriptionResponse.class);
    when(userRepository.findById(1L)).thenReturn(Optional.of(user));
    when(subscriptionRepository.findByIdAndUser(5L, user)).thenReturn(Optional.of(entity));
    when(subscriptionRepository.save(entity)).thenReturn(entity);
    when(subscriptionMapper.toResponse(entity)).thenReturn(response);

    // When
    subscriptionService.resume(1L, 5L);

    // Then
    verify(subscriptionRepository, never()).countByUserAndActiveTrue(any());
  }

  // -------------------------------------------------------------------------
  // delete
  // -------------------------------------------------------------------------

  @Test
  void should_delete_subscription_when_owned_by_user() {
    // Given
    var user = buildUser(1L, UserRole.USER);
    var entity = new Subscription();
    when(userRepository.findById(1L)).thenReturn(Optional.of(user));
    when(subscriptionRepository.findByIdAndUser(5L, user)).thenReturn(Optional.of(entity));

    // When
    subscriptionService.delete(1L, 5L);

    // Then
    verify(subscriptionRepository).delete(entity);
  }

  @Test
  void should_throw_not_found_when_deleting_subscription_not_owned_by_user() {
    // Given
    var user = buildUser(1L, UserRole.USER);
    when(userRepository.findById(1L)).thenReturn(Optional.of(user));
    when(subscriptionRepository.findByIdAndUser(99L, user)).thenReturn(Optional.empty());

    // When / Then
    assertThatThrownBy(() -> subscriptionService.delete(1L, 99L))
        .isInstanceOf(SubscriptionNotFoundException.class);
    verify(subscriptionRepository, never()).delete(any(Subscription.class));
  }

  // -------------------------------------------------------------------------
  // helpers
  // -------------------------------------------------------------------------

  private User buildUser(Long id, UserRole role) {
    var user = new User();
    user.setId(id);
    user.setDisplayName("Test User");
    user.setActive(true);
    user.setRole(role);
    return user;
  }

  private CreateSubscriptionRequest buildCreateRequest(BigDecimal priceDropThreshold) {
    return new CreateSubscriptionRequest(
        "2-комнатные в центре",
        buildCriteria(),
        Set.of(TriggerType.NEW_LISTING),
        DeliveryMode.REALTIME,
        SubscriptionChannelType.TELEGRAM,
        priceDropThreshold,
        null,
        null
    );
  }

  private UpdateSubscriptionRequest buildUpdateRequest(BigDecimal priceDropThreshold) {
    return new UpdateSubscriptionRequest(
        "2-комнатные в центре",
        buildCriteria(),
        Set.of(TriggerType.NEW_LISTING),
        DeliveryMode.REALTIME,
        SubscriptionChannelType.TELEGRAM,
        priceDropThreshold,
        null,
        null
    );
  }

  private SubscriptionSearchCriteria buildCriteria() {
    return new SubscriptionSearchCriteria(
        null, null, null, "Минск", null,
        BigDecimal.valueOf(500), BigDecimal.valueOf(1500), 2, null, null
    );
  }
}
