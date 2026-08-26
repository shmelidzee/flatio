package com.flatio.common.exception;

/** Thrown when a favorite for the requested listing does not exist, or does not belong to the caller. */
public class FavoriteNotFoundException extends RuntimeException {

  public FavoriteNotFoundException(Long listingId) {
    super("Favorite not found for listing: " + listingId);
  }
}
