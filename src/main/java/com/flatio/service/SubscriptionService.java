package com.flatio.service;

import com.flatio.web.dto.CreateSubscriptionRequest;
import com.flatio.web.dto.SubscriptionResponse;
import com.flatio.web.dto.UpdateSubscriptionRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Service for managing user subscriptions to saved search filters.
 */
public interface SubscriptionService {

  /**
   * Creates a new active subscription for the given user.
   *
   * @param userId  the owning user's ID
   * @param request the subscription details
   * @return the created subscription
   * @throws com.flatio.common.exception.SubscriptionLimitExceededException if the user has
   *     reached their tariff's active subscription limit
   */
  SubscriptionResponse create(Long userId, CreateSubscriptionRequest request);

  /**
   * Returns a page of subscriptions owned by the given user.
   *
   * @param userId   the owning user's ID
   * @param pageable pagination and sorting configuration
   * @return page of subscriptions, never null
   */
  Page<SubscriptionResponse> findByUser(Long userId, Pageable pageable);

  /**
   * Returns a single subscription owned by the given user.
   *
   * @param userId the owning user's ID
   * @param id     the subscription ID
   * @return the subscription
   * @throws com.flatio.common.exception.SubscriptionNotFoundException if not found or not owned by the user
   */
  SubscriptionResponse findByIdForUser(Long userId, Long id);

  /**
   * Updates a subscription's name, filter, and delivery settings.
   *
   * @param userId  the owning user's ID
   * @param id      the subscription ID
   * @param request the new subscription details
   * @return the updated subscription
   * @throws com.flatio.common.exception.SubscriptionNotFoundException if not found or not owned by the user
   */
  SubscriptionResponse update(Long userId, Long id, UpdateSubscriptionRequest request);

  /**
   * Pauses an active subscription, stopping notifications until it is resumed.
   *
   * @param userId the owning user's ID
   * @param id     the subscription ID
   * @return the paused subscription
   * @throws com.flatio.common.exception.SubscriptionNotFoundException if not found or not owned by the user
   */
  SubscriptionResponse pause(Long userId, Long id);

  /**
   * Resumes a paused subscription.
   *
   * @param userId the owning user's ID
   * @param id     the subscription ID
   * @return the resumed subscription
   * @throws com.flatio.common.exception.SubscriptionNotFoundException if not found or not owned by the user
   * @throws com.flatio.common.exception.SubscriptionLimitExceededException if resuming would exceed
   *     the user's tariff active subscription limit
   */
  SubscriptionResponse resume(Long userId, Long id);

  /**
   * Deletes a subscription.
   *
   * @param userId the owning user's ID
   * @param id     the subscription ID
   * @throws com.flatio.common.exception.SubscriptionNotFoundException if not found or not owned by the user
   */
  void delete(Long userId, Long id);
}
