package com.flatio.telegram.handler;

import com.flatio.common.util.ImageUrlValidator;
import java.util.Set;
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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
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
    // Full domain set, matching how the single shared ImageUrlValidator bean is wired in production.
    photoProxyClient = new PhotoProxyClient(restClient,
        new ImageUrlValidator(Set.of("onliner.by", "kufar.by", "realt.by")));
    // lenient: the not-allowlisted-host tests below short-circuit before restClient is touched
    lenient().when(restClient.get()).thenReturn(uriSpec);
    lenient().doReturn(headersSpec).when(uriSpec).uri(anyString());
    lenient().when(headersSpec.retrieve()).thenReturn(responseSpec);
  }

  @Test
  void should_return_bytes_when_server_returns_200() {
    // Given
    byte[] expectedBytes = new byte[]{0x01, 0x02, 0x03};
    when(responseSpec.body(byte[].class)).thenReturn(expectedBytes);

    // When
    var result = photoProxyClient.download("https://cdn.onliner.by/photo.jpg", 42L);

    // Then
    assertThat(result).isPresent().contains(expectedBytes);
  }

  @Test
  void should_return_empty_when_response_body_is_null() {
    // Given
    when(responseSpec.body(byte[].class)).thenReturn(null);

    // When
    var result = photoProxyClient.download("https://cdn.onliner.by/photo.jpg", 42L);

    // Then
    assertThat(result).isEmpty();
  }

  @Test
  void should_return_empty_when_response_body_is_zero_length() {
    // Given
    when(responseSpec.body(byte[].class)).thenReturn(new byte[0]);

    // When
    var result = photoProxyClient.download("https://cdn.onliner.by/photo.jpg", 42L);

    // Then
    assertThat(result).isEmpty();
  }

  @Test
  void should_return_empty_when_server_returns_404() {
    // Given
    doThrow(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found", null, null, null))
        .when(responseSpec).body(byte[].class);

    // When
    var result = photoProxyClient.download("https://cdn.onliner.by/missing.jpg", 42L);

    // Then
    assertThat(result).isEmpty();
  }

  @Test
  void should_return_empty_when_server_returns_503() {
    // Given
    doThrow(HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE, "Service Unavailable", null, null, null))
        .when(responseSpec).body(byte[].class);

    // When
    var result = photoProxyClient.download("https://cdn.onliner.by/photo.jpg", 42L);

    // Then
    assertThat(result).isEmpty();
  }

  @Test
  void should_return_empty_when_url_is_not_on_allowlisted_host() {
    // When — attacker-controlled listing photo URL pointing at a non-allowlisted host (SSRF, #364)
    var result = photoProxyClient.download("https://internal.example.com/photo.jpg", 42L);

    // Then — rejected before any outbound request is attempted
    assertThat(result).isEmpty();
    verifyNoInteractions(restClient);
  }

  @Test
  void should_return_empty_when_url_uses_non_https_schema() {
    // When — SSRF vector: plain http bypassing TLS-only CDNs
    var result = photoProxyClient.download("http://cdn.onliner.by/photo.jpg", 42L);

    // Then
    assertThat(result).isEmpty();
    verifyNoInteractions(restClient);
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
