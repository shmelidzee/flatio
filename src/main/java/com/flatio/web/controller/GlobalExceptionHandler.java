package com.flatio.web.controller;

import com.flatio.common.exception.ListingNotFoundException;
import com.flatio.web.dto.ErrorResponse;
import com.flatio.web.dto.ValidationError;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.validation.BindException;
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
