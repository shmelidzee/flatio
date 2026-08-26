package com.flatio.common.exception;

/** Thrown when a user tries to add a listing to favorites beyond their tariff's favorites limit. */
public class FavoriteLimitExceededException extends RuntimeException {

  public FavoriteLimitExceededException(int limit) {
    super("Favorites limit exceeded: limit=" + limit);
  }
}
