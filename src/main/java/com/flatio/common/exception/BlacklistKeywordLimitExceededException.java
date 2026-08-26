package com.flatio.common.exception;

/** Thrown when a user tries to add a stop-word beyond their tariff's blacklist keyword limit. */
public class BlacklistKeywordLimitExceededException extends RuntimeException {

  public BlacklistKeywordLimitExceededException(int limit) {
    super("Blacklist keyword limit exceeded: limit=" + limit);
  }
}
