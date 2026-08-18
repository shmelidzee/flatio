package com.flatio.common.exception;

/**
 * Thrown when an admin attempts to change their own role away from {@code ADMIN}.
 *
 * <p>Prevents the last admin from accidentally locking themselves out of the admin panel.
 */
public class SelfRoleChangeForbiddenException extends RuntimeException {

  public SelfRoleChangeForbiddenException(Long userId) {
    super("Cannot change own role away from ADMIN: userId=" + userId);
  }
}
