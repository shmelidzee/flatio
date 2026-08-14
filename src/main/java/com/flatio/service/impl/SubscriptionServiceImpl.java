package com.flatio.service.impl;

import com.flatio.common.exception.SubscriptionLimitExceededException;
import com.flatio.common.exception.SubscriptionNotFoundException;
import com.flatio.config.SubscriptionLimitProperties;
import com.flatio.domain.subscription.Subscription;
import com.flatio.domain.user.User;
import com.flatio.domain.user.UserRole;
import com.flatio.repository.SubscriptionRepository;
import com.flatio.repository.UserRepository;
import com.flatio.service.SubscriptionService;
import com.flatio.web.dto.CreateSubscriptionRequest;
import com.flatio.web.dto.SubscriptionResponse;
import com.flatio.web.dto.UpdateSubscriptionRequest;
import com.flatio.web.mapper.SubscriptionMapper;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementation of {@link SubscriptionService} enforcing per-user ownership and tariff limits.
 */
@Service
@Transactional(readOnly = true)
@Slf4j
@RequiredArgsConstructor
public class SubscriptionServiceImpl implements SubscriptionService {

  private static final BigDecimal DEFAULT_PRICE_DROP_THRESHOLD = new BigDecimal("5.00");

  private final SubscriptionRepository subscriptionRepository;
  private final UserRepository userRepository;
  private final SubscriptionMapper subscriptionMapper;
  private final SubscriptionLimitProperties limitProperties;

  @Override
  @Transactional
  public SubscriptionResponse create(Long userId, CreateSubscriptionRequest request) {
    User user = getUser(userId);
    enforceActiveLimit(user);

    Subscription subscription = subscriptionMapper.toEntity(request);
    subscription.setUser(user);
    subscription.setActive(true);
    subscription.setPriceDropThreshold(resolvePriceDropThreshold(request.priceDropThreshold()));

    Subscription saved = subscriptionRepository.save(subscription);
    log.info("Subscription created: id={}, userId={}", saved.getId(), userId);
    return subscriptionMapper.toResponse(saved);
  }

  @Override
  public Page<SubscriptionResponse> findByUser(Long userId, Pageable pageable) {
    User user = getUser(userId);
    return subscriptionRepository.findByUser(user, pageable).map(subscriptionMapper::toResponse);
  }

  @Override
  public SubscriptionResponse findByIdForUser(Long userId, Long id) {
    return subscriptionMapper.toResponse(getOwnedSubscription(userId, id));
  }

  @Override
  @Transactional
  public SubscriptionResponse update(Long userId, Long id, UpdateSubscriptionRequest request) {
    Subscription subscription = getOwnedSubscription(userId, id);
    subscriptionMapper.updateEntity(request, subscription);
    subscription.setPriceDropThreshold(resolvePriceDropThreshold(request.priceDropThreshold()));

    Subscription saved = subscriptionRepository.save(subscription);
    return subscriptionMapper.toResponse(saved);
  }

  @Override
  @Transactional
  public SubscriptionResponse pause(Long userId, Long id) {
    Subscription subscription = getOwnedSubscription(userId, id);
    subscription.setActive(false);
    Subscription saved = subscriptionRepository.save(subscription);
    log.info("Subscription paused: id={}, userId={}", id, userId);
    return subscriptionMapper.toResponse(saved);
  }

  @Override
  @Transactional
  public SubscriptionResponse resume(Long userId, Long id) {
    Subscription subscription = getOwnedSubscription(userId, id);
    if (!subscription.isActive()) {
      enforceActiveLimit(subscription.getUser());
      subscription.setActive(true);
    }
    Subscription saved = subscriptionRepository.save(subscription);
    log.info("Subscription resumed: id={}, userId={}", id, userId);
    return subscriptionMapper.toResponse(saved);
  }

  @Override
  @Transactional
  public void delete(Long userId, Long id) {
    Subscription subscription = getOwnedSubscription(userId, id);
    subscriptionRepository.delete(subscription);
    log.info("Subscription deleted: id={}, userId={}", id, userId);
  }

  private User getUser(Long userId) {
    return userRepository.findById(userId)
        .orElseThrow(() -> new IllegalStateException("Authenticated user not found: " + userId));
  }

  private Subscription getOwnedSubscription(Long userId, Long id) {
    User user = getUser(userId);
    return subscriptionRepository.findByIdAndUser(id, user)
        .orElseThrow(() -> new SubscriptionNotFoundException(id));
  }

  private void enforceActiveLimit(User user) {
    int limit = resolveMaxActive(user);
    long activeCount = subscriptionRepository.countByUserAndActiveTrue(user);
    if (activeCount >= limit) {
      throw new SubscriptionLimitExceededException(limit);
    }
  }

  private int resolveMaxActive(User user) {
    return user.getRole() == UserRole.USER ? limitProperties.userMaxActive() : limitProperties.proMaxActive();
  }

  private BigDecimal resolvePriceDropThreshold(BigDecimal requested) {
    return requested != null ? requested : DEFAULT_PRICE_DROP_THRESHOLD;
  }
}
