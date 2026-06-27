package com.flatio.telegram.handler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PhotoProxyClientTest {

  @Mock
  private RestClient restClient;

  @Mock
  @SuppressWarnings("rawtypes")
  private RestClient.RequestHeadersUriSpec uriSpec;

  @Mock
  @SuppressWarnings("rawtypes")
  private RestClient.RequestHeadersSpec headersSpec;

  @Mock
  private RestClient.ResponseSpec responseSpec;

  private PhotoProxyClient photoProxyClient;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    photoProxyClient = new PhotoProxyClient(restClient);
    when(restClient.get()).thenReturn(uriSpec);
    doReturn(headersSpec).when(uriSpec).uri(anyString());
    when(headersSpec.retrieve()).thenReturn(responseSpec);
  }

  @Test
  void should_return_bytes_when_server_returns_200() {
    // Given
    byte[] expectedBytes = new byte[]{0x01, 0x02, 0x03};
    when(responseSpec.body(byte[].class)).thenReturn(expectedBytes);

    // When
    var result = photoProxyClient.download("https://cdn.example.com/photo.jpg", 42L);

    // Then
    assertThat(result).isPresent().contains(expectedBytes);
  }

  @Test
  void should_return_empty_when_response_body_is_null() {
    // Given
    when(responseSpec.body(byte[].class)).thenReturn(null);

    // When
    var result = photoProxyClient.download("https://cdn.example.com/photo.jpg", 42L);

    // Then
    assertThat(result).isEmpty();
  }

  @Test
  void should_return_empty_when_response_body_is_zero_length() {
    // Given
    when(responseSpec.body(byte[].class)).thenReturn(new byte[0]);

    // When
    var result = photoProxyClient.download("https://cdn.example.com/photo.jpg", 42L);

    // Then
    assertThat(result).isEmpty();
  }

  @Test
  void should_return_empty_when_server_returns_404() {
    // Given
    doThrow(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found", null, null, null))
        .when(responseSpec).body(byte[].class);

    // When
    var result = photoProxyClient.download("https://cdn.example.com/missing.jpg", 42L);

    // Then
    assertThat(result).isEmpty();
  }

  @Test
  void should_return_empty_when_server_returns_503() {
    // Given
    doThrow(HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE, "Service Unavailable", null, null, null))
        .when(responseSpec).body(byte[].class);

    // When
    var result = photoProxyClient.download("https://cdn.example.com/photo.jpg", 42L);

    // Then
    assertThat(result).isEmpty();
  }

  @Test
  void should_return_empty_when_connection_times_out() {
    // Given
    doThrow(new ResourceAccessException("I/O error: connect timed out"))
        .when(responseSpec).body(byte[].class);

    // When
    var result = photoProxyClient.download("https://cdn.onliner.by/photo.jpg", 42L);

    // Then
    assertThat(result).isEmpty();
  }

  @Test
  void should_return_empty_when_read_times_out() {
    // Given
    doThrow(new ResourceAccessException("I/O error: read timed out"))
        .when(responseSpec).body(byte[].class);

    // When
    var result = photoProxyClient.download("https://cdn.onliner.by/photo.jpg", 42L);

    // Then
    assertThat(result).isEmpty();
  }
}
