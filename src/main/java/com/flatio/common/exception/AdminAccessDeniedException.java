package com.flatio.common.exception;

/**
 * Thrown when a Telegram identity is verified but does not belong to a registered
 * {@code ADMIN} user. Uses one message regardless of whether the Telegram user is unknown or
 * known-but-not-admin, so the response does not reveal which case occurred.
 */
public class AdminAccessDeniedException extends RuntimeException {

  public AdminAccessDeniedException(String message) {
    super(message);
  }
}
