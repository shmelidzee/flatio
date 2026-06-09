package com.flatio.integration.core;

/**
 * Signals a transient connector failure that warrants a retry attempt.
 *
 * <p>Used to distinguish retryable transport-level errors (e.g. HTTP 429)
 * from non-retryable client errors (other HTTP 4xx) which are handled
 * inline without propagation.
 */
public class ConnectorTransientException extends RuntimeException {

  public ConnectorTransientException(String message, Throwable cause) {
    super(message, cause);
  }
}
