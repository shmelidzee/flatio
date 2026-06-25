package com.flatio.common.exception;

/** Thrown when Telegram WebApp {@code initData} fails signature or freshness validation. */
public class InvalidTelegramAuthException extends RuntimeException {

  public InvalidTelegramAuthException(String message) {
    super(message);
  }
}
