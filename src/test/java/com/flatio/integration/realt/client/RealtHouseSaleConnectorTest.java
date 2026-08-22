package com.flatio.integration.realt.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flatio.integration.core.ConnectorTransientException;
import com.flatio.integration.core.RawListing;
import com.flatio.integration.realt.config.RealtHouseSaleProperties;
import com.flatio.service.CurrencyRateService;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
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
class RealtHouseSaleConnectorTest {

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

  private RealtHouseSaleConnector connector;

  @BeforeEach
  void setUp() {
    var properties = new RealtHouseSaleProperties(
        "https://realt.by",
        "REALT_HOUSE_SALE",
        "BY",
        "/sale/cottages/",
        "/sale-cottages/object/"
    );
    var htmlParser = new RealtHtmlParser(new ObjectMapper(), currencyRateService);
    connector = new RealtHouseSaleConnector(restClient, properties, htmlParser);
  }

  @Test
  void should_return_listings_with_house_sale_types_when_valid_html_provided() throws IOException {
    // Given
    String html = loadFixture("fixtures/realt/valid-listing-page.html");
    mockRestClientReturning(html);

    // When
    List<RawListing> result = connector.fetch();

    // Then — dealType must be SELL, propertyType must be HOUSE
    assertThat(result).isNotEmpty();
    assertThat(result.get(0).dealType()).isEqualTo("SELL");
    assertThat(result.get(0).propertyType()).isEqualTo("HOUSE");
  }

  @Test
  void should_return_source_id_and_region_from_properties() {
    assertThat(connector.getSourceId()).isEqualTo("REALT_HOUSE_SALE");
    assertThat(connector.getSupportedRegionCode()).isEqualTo("BY");
  }

  @Test
  void should_use_house_sale_specific_source_url_in_listings() throws IOException {
    // Given
    String html = loadFixture("fixtures/realt/valid-listing-page.html");
    mockRestClientReturning(html);

    // When
    List<RawListing> result = connector.fetch();

    // Then
    assertThat(result.get(0).sourceUrl()).contains("/sale-cottages/object/");
  }

  @Test
  void should_return_empty_list_when_404_received() {
    // Given — realt.by changed its URL structure; connector must degrade gracefully
    var exception = HttpClientErrorException.create(
        HttpStatus.NOT_FOUND, "Not Found", new HttpHeaders(), null, null);
    mockRestClientThrowing(exception);

    // When
    List<RawListing> result = connector.fetch();

    // Then — no exception propagated, empty list returned
    assertThat(result).isEmpty();
  }

  @Test
  void should_return_empty_list_when_response_is_null() {
    // Given
    mockRestClientReturning(null);

    // When / Then
    assertThat(connector.fetch()).isEmpty();
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
  @SuppressWarnings({"unchecked", "rawtypes"})
  void should_throw_when_full_fetch_hits_429_after_first_page_already_collected() throws IOException {
    // Given — first page succeeds with a listing and a next-page link, second page hits 429
    String pageWithNext = loadFixture("fixtures/realt/listing-page-with-pagination.html");
    var retryHeaders = new HttpHeaders();
    retryHeaders.set(HttpHeaders.RETRY_AFTER, "0");
    var exception = HttpClientErrorException.create(
        HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests", retryHeaders, null, null);
    when(restClient.get()).thenReturn(requestHeadersUriSpec);
    when(requestHeadersUriSpec.uri(any(Function.class))).thenReturn(requestHeadersSpec);
    when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
    when(responseSpec.body(String.class)).thenReturn(pageWithNext).thenThrow(exception);

    // When / Then — a full fetch's result feeds the missed-sync deactivation penalty, so a
    // page-range-truncated partial result must not be returned as if it were complete; rethrowing
    // lets Resilience4j retry the whole fetch from page 1 instead
    assertThatThrownBy(() -> connector.fetch())
        .isInstanceOf(ConnectorTransientException.class)
        .hasCauseInstanceOf(HttpClientErrorException.TooManyRequests.class);
  }

  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void should_return_partial_result_when_delta_fetch_hits_429_after_first_page_already_collected() throws IOException {
    // Given — first page succeeds with a listing and a next-page link, second page hits 429
    String pageWithNext = loadFixture("fixtures/realt/listing-page-with-pagination.html");
    var retryHeaders = new HttpHeaders();
    retryHeaders.set(HttpHeaders.RETRY_AFTER, "0");
    var exception = HttpClientErrorException.create(
        HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests", retryHeaders, null, null);
    when(restClient.get()).thenReturn(requestHeadersUriSpec);
    when(requestHeadersUriSpec.uri(any(Function.class))).thenReturn(requestHeadersSpec);
    when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
    when(responseSpec.body(String.class)).thenReturn(pageWithNext).thenThrow(exception);

    // When — delta results never drive deactivation, so no exception propagates here, unlike
    // the full-fetch case above
    List<RawListing> result = connector.fetchDelta(Instant.parse("2020-01-01T00:00:00Z"));

    // Then — page 1's listing is kept instead of being discarded by a full retry
    assertThat(result).hasSize(1);
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
    assertThat(connector.fetchFallback(new RestClientException("Connection refused"))).isEmpty();
  }

  @Test
  void should_not_throw_from_fallback_method() {
    assertThatNoException().isThrownBy(() -> connector.fetchFallback(new RuntimeException("error")));
  }

  @Test
  void should_return_default_retry_after_when_headers_are_null() {
    assertThat(connector.parseRetryAfterSeconds(null)).isEqualTo(5L);
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
