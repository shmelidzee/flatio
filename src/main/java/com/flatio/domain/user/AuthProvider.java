package com.flatio.domain.user;

/**
 * Authentication provider types supported by the platform.
 *
 * <p>TELEGRAM is the primary provider for MVP 1. GOOGLE and EMAIL are reserved for MVP 2.
 */
public enum AuthProvider {
  TELEGRAM,
  GOOGLE,
  EMAIL
}
