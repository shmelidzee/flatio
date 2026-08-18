package com.flatio.common.exception;

/** Thrown when a user with the requested id does not exist in the database. */
public class UserNotFoundException extends RuntimeException {

  public UserNotFoundException(Long id) {
    super("User not found: " + id);
  }
}
