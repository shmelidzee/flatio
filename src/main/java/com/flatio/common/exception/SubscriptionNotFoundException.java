package com.flatio.common.exception;

/** Thrown when a subscription with the requested ID does not exist, or does not belong to the caller. */
public class SubscriptionNotFoundException extends RuntimeException {

  public SubscriptionNotFoundException(Long id) {
    super("Subscription not found: " + id);
  }
}
