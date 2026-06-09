package com.flatio.common.exception;

/** Thrown when a listing with the requested ID does not exist in the database. */
public class ListingNotFoundException extends RuntimeException {

  public ListingNotFoundException(Long id) {
    super("Listing not found: " + id);
  }
}
