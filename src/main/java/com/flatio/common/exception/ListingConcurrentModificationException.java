package com.flatio.common.exception;

/**
 * Thrown when an admin action on a listing conflicts with a concurrent update to the same
 * record (e.g. a background sync job updating it in the same moment).
 */
public class ListingConcurrentModificationException extends RuntimeException {

  public ListingConcurrentModificationException(Long id) {
    super("Listing was modified concurrently, please retry: " + id);
  }
}
