package com.flatio.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.HttpRequestMethodNotSupportedException;

@ExtendWith(MockitoExtension.class)
class GlobalExceptionHandlerTest {

  @Mock
  private HttpServletRequest request;

  private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

  @Test
  void should_return_405_when_method_not_supported_exception_thrown() {
    // Given
    when(request.getRequestURI()).thenReturn("/admin");
    var ex = new HttpRequestMethodNotSupportedException("GET");

    // When
    var result = handler.handleMethodNotSupported(ex, request);

    // Then
    assertThat(result.status()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED.value());
    assertThat(result.path()).isEqualTo("/admin");
    assertThat(result.message()).contains("GET");
  }
}
