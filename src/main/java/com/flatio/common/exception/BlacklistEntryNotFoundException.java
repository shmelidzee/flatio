package com.flatio.common.exception;

/** Thrown when a blacklist entry with the requested ID does not exist, or does not belong to the caller. */
public class BlacklistEntryNotFoundException extends RuntimeException {

  public BlacklistEntryNotFoundException(Long id) {
    super("Blacklist entry not found: " + id);
  }
}
