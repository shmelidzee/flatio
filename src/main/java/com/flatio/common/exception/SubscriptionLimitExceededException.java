package com.flatio.common.exception;

/** Thrown when a user tries to activate a subscription beyond their tariff's active subscription limit. */
public class SubscriptionLimitExceededException extends RuntimeException {

  public SubscriptionLimitExceededException(int limit) {
    super("Active subscription limit exceeded: limit=" + limit);
  }
}
