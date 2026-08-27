package com.flatio.web.controller;

import com.flatio.common.exception.AdminAccessDeniedException;
import com.flatio.common.exception.BlacklistEntryNotFoundException;
import com.flatio.common.exception.BlacklistInvalidValueException;
import com.flatio.common.exception.BlacklistKeywordLimitExceededException;
import com.flatio.common.exception.FavoriteLimitExceededException;
import com.flatio.common.exception.FavoriteNotFoundException;
import com.flatio.common.exception.InvalidTelegramAuthException;
import com.flatio.common.exception.ListingConcurrentModificationException;
import com.flatio.common.exception.ListingNotFoundException;
import com.flatio.common.exception.SelfRoleChangeForbiddenException;
import com.flatio.common.exception.SourceNotFoundException;
import com.flatio.common.exception.SubscriptionLimitExceededException;
import com.flatio.common.exception.SubscriptionNotFoundException;
import com.flatio.common.exception.UserNotFoundException;
import com.flatio.web.dto.ErrorResponse;
import com.flatio.web.dto.ValidationError;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.validation.BindException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Centralised HTTP error handling for all REST controllers.
 *
 * <p>4xx errors are logged at WARN level; 5xx errors at ERROR level with the full stack trace.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

  @ExceptionHandler(ListingNotFoundException.class)
  @ResponseStatus(HttpStatus.NOT_FOUND)
  public ErrorResponse handleListingNotFound(ListingNotFoundException ex, HttpServletRequest request) {
    log.warn("Resource not found on {}: {}", request.getRequestURI(), ex.getMessage());
    return buildError(HttpStatus.NOT_FOUND, ex.getMessage(), request.getRequestURI(), List.of());
  }

  @ExceptionHandler(ListingConcurrentModificationException.class)
  @ResponseStatus(HttpStatus.CONFLICT)
  public ErrorResponse handleListingConcurrentModification(
      ListingConcurrentModificationException ex, HttpServletRequest request
  ) {
    log.warn("Concurrent modification on {}: {}", request.getRequestURI(), ex.getMessage());
    return buildError(HttpStatus.CONFLICT, ex.getMessage(), request.getRequestURI(), List.of());
  }

  @ExceptionHandler(SourceNotFoundException.class)
  @ResponseStatus(HttpStatus.NOT_FOUND)
  public ErrorResponse handleSourceNotFound(SourceNotFoundException ex, HttpServletRequest request) {
    log.warn("Resource not found on {}: {}", request.getRequestURI(), ex.getMessage());
    return buildError(HttpStatus.NOT_FOUND, ex.getMessage(), request.getRequestURI(), List.of());
  }

  @ExceptionHandler(SubscriptionNotFoundException.class)
  @ResponseStatus(HttpStatus.NOT_FOUND)
  public ErrorResponse handleSubscriptionNotFound(SubscriptionNotFoundException ex, HttpServletRequest request) {
    log.warn("Resource not found on {}: {}", request.getRequestURI(), ex.getMessage());
    return buildError(HttpStatus.NOT_FOUND, ex.getMessage(), request.getRequestURI(), List.of());
  }

  @ExceptionHandler(SubscriptionLimitExceededException.class)
  @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
  public ErrorResponse handleSubscriptionLimitExceeded(
      SubscriptionLimitExceededException ex, HttpServletRequest request
  ) {
    log.warn("Subscription limit exceeded on {}: {}", request.getRequestURI(), ex.getMessage());
    return buildError(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), request.getRequestURI(), List.of());
  }

  @ExceptionHandler(FavoriteNotFoundException.class)
  @ResponseStatus(HttpStatus.NOT_FOUND)
  public ErrorResponse handleFavoriteNotFound(FavoriteNotFoundException ex, HttpServletRequest request) {
    log.warn("Resource not found on {}: {}", request.getRequestURI(), ex.getMessage());
    return buildError(HttpStatus.NOT_FOUND, ex.getMessage(), request.getRequestURI(), List.of());
  }

  @ExceptionHandler(FavoriteLimitExceededException.class)
  @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
  public ErrorResponse handleFavoriteLimitExceeded(FavoriteLimitExceededException ex, HttpServletRequest request) {
    log.warn("Favorites limit exceeded on {}: {}", request.getRequestURI(), ex.getMessage());
    return buildError(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), request.getRequestURI(), List.of());
  }

  @ExceptionHandler(BlacklistEntryNotFoundException.class)
  @ResponseStatus(HttpStatus.NOT_FOUND)
  public ErrorResponse handleBlacklistEntryNotFound(BlacklistEntryNotFoundException ex, HttpServletRequest request) {
    log.warn("Resource not found on {}: {}", request.getRequestURI(), ex.getMessage());
    return buildError(HttpStatus.NOT_FOUND, ex.getMessage(), request.getRequestURI(), List.of());
  }

  @ExceptionHandler(BlacklistKeywordLimitExceededException.class)
  @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
  public ErrorResponse handleBlacklistKeywordLimitExceeded(
      BlacklistKeywordLimitExceededException ex, HttpServletRequest request
  ) {
    log.warn("Blacklist keyword limit exceeded on {}: {}", request.getRequestURI(), ex.getMessage());
    return buildError(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), request.getRequestURI(), List.of());
  }

  @ExceptionHandler(BlacklistInvalidValueException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public ErrorResponse handleBlacklistInvalidValue(BlacklistInvalidValueException ex, HttpServletRequest request) {
    log.warn("Invalid blacklist value on {}: {}", request.getRequestURI(), ex.getMessage());
    return buildError(HttpStatus.BAD_REQUEST, ex.getMessage(), request.getRequestURI(), List.of());
  }

  @ExceptionHandler(InvalidTelegramAuthException.class)
  @ResponseStatus(HttpStatus.UNAUTHORIZED)
  public ErrorResponse handleInvalidTelegramAuth(InvalidTelegramAuthException ex, HttpServletRequest request) {
    log.warn("Telegram initData validation failed on {}: {}", request.getRequestURI(), ex.getMessage());
    return buildError(HttpStatus.UNAUTHORIZED, ex.getMessage(), request.getRequestURI(), List.of());
  }

  @ExceptionHandler(AdminAccessDeniedException.class)
  @ResponseStatus(HttpStatus.FORBIDDEN)
  public ErrorResponse handleAdminAccessDenied(AdminAccessDeniedException ex, HttpServletRequest request) {
    log.warn("Admin access denied on {}: {}", request.getRequestURI(), ex.getMessage());
    return buildError(HttpStatus.FORBIDDEN, ex.getMessage(), request.getRequestURI(), List.of());
  }

  @ExceptionHandler(UserNotFoundException.class)
  @ResponseStatus(HttpStatus.NOT_FOUND)
  public ErrorResponse handleUserNotFound(UserNotFoundException ex, HttpServletRequest request) {
    log.warn("Resource not found on {}: {}", request.getRequestURI(), ex.getMessage());
    return buildError(HttpStatus.NOT_FOUND, ex.getMessage(), request.getRequestURI(), List.of());
  }

  @ExceptionHandler(SelfRoleChangeForbiddenException.class)
  @ResponseStatus(HttpStatus.FORBIDDEN)
  public ErrorResponse handleSelfRoleChangeForbidden(SelfRoleChangeForbiddenException ex, HttpServletRequest request) {
    log.warn("Self role change denied on {}: {}", request.getRequestURI(), ex.getMessage());
    return buildError(HttpStatus.FORBIDDEN, ex.getMessage(), request.getRequestURI(), List.of());
  }

  @ExceptionHandler(BindException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public ErrorResponse handleBindException(BindException ex, HttpServletRequest request) {
    List<ValidationError> errors = ex.getBindingResult().getFieldErrors().stream()
        .map(fe -> new ValidationError(fe.getField(), fe.getDefaultMessage()))
        .toList();
    log.warn("Binding failed on {}: {} field error(s)", request.getRequestURI(), errors.size());
    return buildError(HttpStatus.BAD_REQUEST, "Invalid request parameters", request.getRequestURI(), errors);
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public ErrorResponse handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
    List<ValidationError> errors = ex.getBindingResult().getFieldErrors().stream()
        .map(fe -> new ValidationError(fe.getField(), fe.getDefaultMessage()))
        .toList();
    log.warn("Validation failed on {}: {} field error(s)", request.getRequestURI(), errors.size());
    return buildError(HttpStatus.BAD_REQUEST, "Validation failed", request.getRequestURI(), errors);
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public ErrorResponse handleTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
    String message = String.format("Invalid value '%s' for parameter '%s'", ex.getValue(), ex.getName());
    log.warn("Type mismatch on {}: {}", request.getRequestURI(), message);
    return buildError(HttpStatus.BAD_REQUEST, message, request.getRequestURI(), List.of());
  }

  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
  public ErrorResponse handleMethodNotSupported(
      HttpRequestMethodNotSupportedException ex, HttpServletRequest request
  ) {
    // Without this handler, a path registered under a different HTTP method (issue #361 — the
    // Telegram webhook is mapped at a path derived from configuration and can collide with an
    // unrelated route) fell through to handleUnexpected below and returned a misleading 500
    // instead of the 405 this actually is.
    log.warn("Method not allowed on {}: {}", request.getRequestURI(), ex.getMessage());
    return buildError(HttpStatus.METHOD_NOT_ALLOWED, ex.getMessage(), request.getRequestURI(), List.of());
  }

  @ExceptionHandler(Exception.class)
  @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
  public ErrorResponse handleUnexpected(Exception ex, HttpServletRequest request) {
    log.error("Unexpected error on {}", request.getRequestURI(), ex);
    return buildError(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error", request.getRequestURI(), List.of());
  }

  private ErrorResponse buildError(HttpStatus status, String message, String path, List<ValidationError> errors) {
    return new ErrorResponse(Instant.now(), status.value(), status.getReasonPhrase(), message, path, errors);
  }
}
