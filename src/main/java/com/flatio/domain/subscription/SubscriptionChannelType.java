package com.flatio.domain.subscription;

/**
 * Delivery channel for subscription notifications.
 *
 * <p>TELEGRAM is the only channel available for MVP 1, matching
 * {@link com.flatio.domain.user.AuthProvider}. EMAIL is reserved for a future market.
 */
public enum SubscriptionChannelType {
  TELEGRAM
}
