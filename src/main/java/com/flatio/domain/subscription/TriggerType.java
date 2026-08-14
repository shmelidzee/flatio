package com.flatio.domain.subscription;

/**
 * Events on a matching listing that can trigger a subscription notification.
 */
public enum TriggerType {
  NEW_LISTING,
  PRICE_DROP,
  REPOSTED,
  REACTIVATED
}
