package com.flatio.domain.user;

/**
 * Roles available to platform users.
 *
 * <p>USER is the default role assigned to every new account.
 * PRO marks a paid-tier account with higher subscription limits.
 * ADMIN grants access to administrative endpoints and privileged operations.
 */
public enum UserRole {
  USER,
  PRO,
  ADMIN
}
