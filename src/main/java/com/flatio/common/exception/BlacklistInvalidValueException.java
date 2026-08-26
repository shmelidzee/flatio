package com.flatio.common.exception;

import com.flatio.domain.blacklist.BlacklistEntryType;

/** Thrown when a blacklist entry's {@code value} does not match the format required by its {@code type}. */
public class BlacklistInvalidValueException extends RuntimeException {

  public BlacklistInvalidValueException(BlacklistEntryType type, String value) {
    super("Invalid blacklist value for type " + type + ": " + value);
  }
}
