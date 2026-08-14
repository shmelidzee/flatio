package com.flatio.common.exception;

/** Thrown when a source with the requested code does not exist in the database. */
public class SourceNotFoundException extends RuntimeException {

  public SourceNotFoundException(String sourceId) {
    super("Source not found: " + sourceId);
  }
}
