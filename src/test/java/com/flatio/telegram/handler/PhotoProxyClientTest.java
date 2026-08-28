package com.flatio.telegram.handler;

import com.flatio.common.util.ImageUrlValidator;
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

  // Mocked rather than constructed for real (issue #455 QA follow-up): this class tests that
  // PhotoProxyClient correctly calls ImageUrlValidator and honors its verdict, not
  // ImageUrlValidator's own loopback/private/link-local/DNS-resolution logic — that is already
  // exhaustively covered in its own unit test (ImageUrlValidatorTest). A real instance would
  // otherwise require a live DNS lookup to "cdn.onliner.by" on every happy-path test run, which is
  // unnecessary here and unsafe for a network-restricted CI.
  @Mock
  private ImageUrlValidator imageUrlValidator;

  private PhotoProxyClient photoProxyClient;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    photoProxyClient = new PhotoProxyClient(restClient, imageUrlValidator);
    // lenient: only the SSRF-guard/scheme tests below override this to false, and short-circuit
    // before restClient is touched
    lenient().when(imageUrlValidator.isAllowedImageUrl(anyString())).thenReturn(true);
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
  void should_return_empty_when_url_points_to_loopback_host() {
    // Given — attacker-controlled listing photo URL pointing at a loopback address (SSRF, #364/#450)
    when(imageUrlValidator.isAllowedImageUrl("https://127.0.0.1/photo.jpg")).thenReturn(false);

    // When
    var result = photoProxyClient.download("https://127.0.0.1/photo.jpg", 42L);

    // Then — rejected before any outbound request is attempted
    assertThat(result).isEmpty();
    verifyNoInteractions(restClient);
  }

  @Test
  void should_return_empty_when_url_points_to_link_local_cloud_metadata_host() {
    // Given — attacker-controlled listing photo URL pointing at the cloud metadata endpoint
    when(imageUrlValidator.isAllowedImageUrl("https://169.254.169.254/latest/meta-data/")).thenReturn(false);

    // When
    var result = photoProxyClient.download("https://169.254.169.254/latest/meta-data/", 42L);

    // Then — rejected before any outbound request is attempted
    assertThat(result).isEmpty();
    verifyNoInteractions(restClient);
  }

  @Test
  void should_return_empty_when_url_uses_non_https_schema() {
    // Given — SSRF vector: plain http bypassing TLS-only CDNs
    when(imageUrlValidator.isAllowedImageUrl("http://cdn.onliner.by/photo.jpg")).thenReturn(false);

    // When
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
