package com.flatio.integration.realt.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flatio.integration.core.ConnectorTransientException;
import com.flatio.integration.core.RawListing;
import com.flatio.integration.realt.config.RealtRoomProperties;
import com.flatio.service.CurrencyRateService;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RealtRoomConnectorTest {

  @Mock
  private RestClient restClient;

  @Mock
  @SuppressWarnings("rawtypes")
  private RestClient.RequestHeadersUriSpec requestHeadersUriSpec;

  @Mock
  @SuppressWarnings("rawtypes")
  private RestClient.RequestHeadersSpec requestHeadersSpec;

  @Mock
  private RestClient.ResponseSpec responseSpec;

  @Mock
  private CurrencyRateService currencyRateService;

  private RealtRoomConnector connector;

  @BeforeEach
  void setUp() {
    var properties = new RealtRoomProperties(
        "https://realt.by",
        "REALT_ROOM",
        "BY",
        "/rent/room-for-long/",
        "/rent-rooms-for-long/object/"
    );
    var htmlParser = new RealtHtmlParser(new ObjectMapper(), currencyRateService);
    connector = new RealtRoomConnector(restClient, properties, htmlParser);
  }

  @Test
  void should_return_listings_with_room_property_type_when_valid_html_provided() throws IOException {
    // Given
    String html = loadFixture("fixtures/realt/valid-listing-page.html");
    mockRestClientReturning(html);

    // When
    List<RawListing> result = connector.fetch();

    // Then — propertyType must be ROOM (from connector constants, not APARTMENT)
    assertThat(result).isNotEmpty();
    assertThat(result.get(0).dealType()).isEqualTo("RENT");
    assertThat(result.get(0).propertyType()).isEqualTo("ROOM");
  }

  @Test
  void should_return_source_id_and_region_from_properties() {
    // When / Then
    assertThat(connector.getSourceId()).isEqualTo("REALT_ROOM");
    assertThat(connector.getSupportedRegionCode()).isEqualTo("BY");
  }

  @Test
  void should_use_room_specific_source_url_in_listings() throws IOException {
    // Given
    String html = loadFixture("fixtures/realt/valid-listing-page.html");
    mockRestClientReturning(html);

    // When
    List<RawListing> result = connector.fetch();

    // Then — source URL uses room-specific path, not flat path
    assertThat(result.get(0).sourceUrl())
        .contains("/rent-rooms-for-long/object/");
  }

  @Test
  void should_return_empty_list_when_response_is_null() {
    // Given
    mockRestClientReturning(null);

    // When
    List<RawListing> result = connector.fetch();

    // Then
    assertThat(result).isEmpty();
  }

  @Test
  void should_throw_connector_transient_exception_when_429_received() {
    // Given
    var retryHeaders = new HttpHeaders();
    retryHeaders.set(HttpHeaders.RETRY_AFTER, "0");
    var exception = HttpClientErrorException.create(
        HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests", retryHeaders, null, null);
    mockRestClientThrowing(exception);

    // When / Then
    assertThatThrownBy(() -> connector.fetch())
        .isInstanceOf(ConnectorTransientException.class);
  }

  @Test
  void should_return_empty_list_when_non_retryable_4xx_received() {
    // Given
    var exception = HttpClientErrorException.create(
        HttpStatus.NOT_FOUND, "Not Found", HttpHeaders.EMPTY, null, null);
    mockRestClientThrowing(exception);

    // When
    List<RawListing> result = connector.fetch();

    // Then
    assertThat(result).isEmpty();
  }

  @Test
  void should_propagate_server_exception_when_5xx_received() {
    // Given
    var exception = new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE);
    mockRestClientThrowing(exception);

    // When / Then
    assertThatThrownBy(() -> connector.fetch())
        .isInstanceOf(HttpServerErrorException.class);
  }

  @Test
  void should_return_empty_list_when_fallback_is_invoked_after_exhausted_retries() {
    // Given
    var exception = new RestClientException("Connection refused");

    // When
    List<RawListing> result = connector.fetchFallback(exception);

    // Then
    assertThat(result).isEmpty();
  }

  @Test
  void should_not_throw_from_fallback_method() {
    // Given
    var exception = new RuntimeException("Service unavailable");

    // When / Then
    assertThatNoException().isThrownBy(() -> connector.fetchFallback(exception));
  }

  @Test
  void should_return_default_retry_after_when_headers_are_null() {
    assertThat(connector.parseRetryAfterSeconds(null)).isEqualTo(5L);
  }

  @Test
  void should_cap_retry_after_at_60_seconds_when_header_exceeds_maximum() {
    // Given
    var headers = new HttpHeaders();
    headers.set(HttpHeaders.RETRY_AFTER, "999");

    // When / Then
    assertThat(connector.parseRetryAfterSeconds(headers)).isEqualTo(60L);
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private void mockRestClientReturning(String html) {
    when(restClient.get()).thenReturn(requestHeadersUriSpec);
    when(requestHeadersUriSpec.uri(any(Function.class))).thenReturn(requestHeadersSpec);
    when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
    when(responseSpec.body(String.class)).thenReturn(html);
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private void mockRestClientThrowing(RuntimeException exception) {
    when(restClient.get()).thenReturn(requestHeadersUriSpec);
    when(requestHeadersUriSpec.uri(any(Function.class))).thenReturn(requestHeadersSpec);
    when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
    when(responseSpec.body(String.class)).thenThrow(exception);
  }

  private String loadFixture(String path) throws IOException {
    InputStream stream = getClass().getClassLoader().getResourceAsStream(path);
    assertThat(stream).as("fixture file not found on classpath: %s", path).isNotNull();
    return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
  }
}
