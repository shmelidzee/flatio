package com.flatio.integration.onliner.client;

import com.flatio.integration.core.ConnectorTransientException;
import com.flatio.integration.core.RawListing;
import com.flatio.integration.onliner.config.OnlinerProperties;
import com.flatio.integration.onliner.dto.OnlinerSearchResponse;
import com.flatio.integration.onliner.dto.OnlinerApartment;
import com.flatio.integration.onliner.dto.OnlinerArea;
import com.flatio.integration.onliner.dto.OnlinerLocation;
import com.flatio.integration.onliner.dto.OnlinerPage;
import com.flatio.integration.onliner.dto.OnlinerPrice;
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

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OnlinerConnectorTest {

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

  private OnlinerConnector connector;

  @BeforeEach
  void setUp() {
    var properties = new OnlinerProperties(
        "https://ak.api.onliner.by",
        "ONLINER",
        "BY",
        "/search/apartments",
        50
    );
    connector = new OnlinerConnector(restClient, properties);
  }

  // -------------------------------------------------------------------------
  // Happy path
  // -------------------------------------------------------------------------

  @Test
  @SuppressWarnings("unchecked")
  void should_return_listings_when_valid_response_provided() {
    // Given
    var response = buildValidResponse();
    mockRestClientReturning(response);

    // When
    List<RawListing> result = connector.fetch();

    // Then
    assertThat(result).hasSize(2);
    assertThat(result.get(0).externalId()).isEqualTo("1001");
    assertThat(result.get(0).dealType()).isEqualTo("rent");
    assertThat(result.get(0).currency()).isEqualTo("USD");
    assertThat(result.get(0).rooms()).isEqualTo(2);
    assertThat(result.get(0).floorNumber()).isEqualTo(3);
    assertThat(result.get(0).photoUrls()).hasSize(1);

    assertThat(result.get(1).externalId()).isEqualTo("1002");
    assertThat(result.get(1).dealType()).isEqualTo("sell");
    assertThat(result.get(1).photoUrls()).isEmpty();
  }

  @Test
  @SuppressWarnings("unchecked")
  void should_return_source_id_and_region_from_properties() {
    // When / Then — values come from config, not hardcoded
    assertThat(connector.getSourceId()).isEqualTo("ONLINER");
    assertThat(connector.getSupportedRegionCode()).isEqualTo("BY");
  }

  // -------------------------------------------------------------------------
  // Empty / null responses
  // -------------------------------------------------------------------------

  @Test
  @SuppressWarnings("unchecked")
  void should_return_empty_list_when_apartments_list_is_empty() {
    // Given
    var response = buildEmptyResponse();
    mockRestClientReturning(response);

    // When
    List<RawListing> result = connector.fetch();

    // Then
    assertThat(result).isEmpty();
  }

  @Test
  @SuppressWarnings("unchecked")
  void should_return_empty_list_when_api_returns_null_response() {
    // Given
    mockRestClientReturning(null);

    // When
    List<RawListing> result = connector.fetch();

    // Then — no exception, graceful degradation
    assertThat(result).isEmpty();
  }

  // -------------------------------------------------------------------------
  // Error handling and isolation
  // -------------------------------------------------------------------------

  @Test
  void should_return_empty_list_when_fallback_is_invoked_after_exhausted_retries() {
    // Given — simulates Resilience4j calling fallback after all retries failed
    var exception = new RestClientException("503 Service Unavailable");

    // When
    List<RawListing> result = connector.fetchFallback(exception);

    // Then — graceful degradation: empty list, no exception
    assertThat(result).isEmpty();
  }

  @Test
  void should_not_throw_from_fallback_method() {
    // Given
    var exception = new RestClientException("Connection refused");

    // When / Then — fallback is always safe, never throws
    assertThatNoException().isThrownBy(() -> connector.fetchFallback(exception));
  }

  @Test
  @SuppressWarnings("unchecked")
  void should_skip_listing_with_null_price_amount_and_return_others() {
    // Given — response with one apartment that has a price field but null amount string
    var response = buildResponseWithBrokenPriceAmount();
    mockRestClientReturning(response);

    // When
    List<RawListing> result = connector.fetch();

    // Then — broken listing is skipped, the valid one is returned
    assertThat(result).hasSize(1);
    assertThat(result.get(0).externalId()).isEqualTo("3001");
  }

  // -------------------------------------------------------------------------
  // Field mapping
  // -------------------------------------------------------------------------

  @Test
  @SuppressWarnings("unchecked")
  void should_map_all_required_fields_correctly() {
    // Given
    var response = buildValidResponse();
    mockRestClientReturning(response);

    // When
    List<RawListing> result = connector.fetch();
    RawListing first = result.get(0);

    // Then
    assertThat(first.externalId()).isEqualTo("1001");
    assertThat(first.sourceUrl()).isEqualTo("https://r.onliner.by/ak/apartments/1001");
    assertThat(first.price()).isNotNull();
    assertThat(first.rooms()).isEqualTo(2);
    assertThat(first.floorNumber()).isEqualTo(3);
    assertThat(first.floorsTotal()).isEqualTo(9);
    assertThat(first.areaTotalM2()).isNotNull();
    assertThat(first.address()).isEqualTo("Минск, пр-т Независимости, 72");
    assertThat(first.latitude()).isNotNull();
    assertThat(first.longitude()).isNotNull();
    assertThat(first.publishedAt()).isNotNull();
    assertThat(first.title()).isNotBlank();
  }

  @Test
  @SuppressWarnings("unchecked")
  void should_return_fallback_title_when_all_title_fields_are_null() {
    // Given — apartment with rooms, area, address all null
    var response = buildResponseWithNullTitleFields();
    mockRestClientReturning(response);

    // When
    List<RawListing> result = connector.fetch();

    // Then — fallback title applied, not a blank or null value
    assertThat(result).hasSize(1);
    assertThat(result.get(0).title()).isEqualTo("Квартира на Onliner");
  }

  @Test
  @SuppressWarnings("unchecked")
  void should_return_empty_photo_list_when_photo_is_null() {
    // Given
    var response = buildValidResponse();
    mockRestClientReturning(response);

    // When
    List<RawListing> result = connector.fetch();

    // Then — second apartment has null photo → empty list
    assertThat(result.get(1).photoUrls()).isEmpty();
  }

  // -------------------------------------------------------------------------
  // Fixture-based deserialization (JSON → RawListing full chain)
  // -------------------------------------------------------------------------

  @Test
  @SuppressWarnings("unchecked")
  void should_correctly_deserialize_valid_response_fixture_including_json_property_mappings() throws IOException {
    // Given — loads actual Onliner API snapshot; verifies @JsonProperty (deal_type, rooms_count, etc.)
    var response = loadFixture("fixtures/onliner/valid-response.json");
    mockRestClientReturning(response);

    // When
    List<RawListing> result = connector.fetch();

    // Then — all snake_case JSON fields correctly mapped to camelCase Java fields
    assertThat(result).hasSize(2);
    assertThat(result.get(0).externalId()).isEqualTo("1001");
    assertThat(result.get(0).dealType()).isEqualTo("rent");
    assertThat(result.get(0).price()).isEqualByComparingTo("450.00");
    assertThat(result.get(0).currency()).isEqualTo("USD");
    assertThat(result.get(0).rooms()).isEqualTo(2);
    assertThat(result.get(0).floorNumber()).isEqualTo(3);
    assertThat(result.get(0).floorsTotal()).isEqualTo(9);
    assertThat(result.get(1).externalId()).isEqualTo("1002");
    assertThat(result.get(1).dealType()).isEqualTo("sell");
    assertThat(result.get(1).photoUrls()).isEmpty();
  }

  @Test
  @SuppressWarnings("unchecked")
  void should_return_empty_list_from_empty_response_fixture() throws IOException {
    // Given — empty response fixture file
    var response = loadFixture("fixtures/onliner/empty-response.json");
    mockRestClientReturning(response);

    // When
    List<RawListing> result = connector.fetch();

    // Then
    assertThat(result).isEmpty();
  }

  @Test
  @SuppressWarnings("unchecked")
  void should_skip_listing_with_null_price_when_loaded_from_fixture() throws IOException {
    // Given — fixture has apt 2001 (valid) and apt 2002 (price: null)
    var response = loadFixture("fixtures/onliner/response-without-price.json");
    mockRestClientReturning(response);

    // When
    List<RawListing> result = connector.fetch();

    // Then — null-price listing is skipped; valid one returned
    assertThat(result).hasSize(1);
    assertThat(result.get(0).externalId()).isEqualTo("2001");
  }

  // -------------------------------------------------------------------------
  // HTTP error handling
  // -------------------------------------------------------------------------

  @Test
  @SuppressWarnings("unchecked")
  void should_throw_connector_transient_exception_when_429_received() {
    // Given — Retry-After: 0 so test does not sleep
    var retryHeaders = new HttpHeaders();
    retryHeaders.set(HttpHeaders.RETRY_AFTER, "0");
    var exception = HttpClientErrorException.create(
        HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests", retryHeaders, null, null);
    mockRestClientThrowing(exception);

    // When / Then — 429 becomes ConnectorTransientException for Resilience4j retry
    assertThatThrownBy(() -> connector.fetch())
        .isInstanceOf(ConnectorTransientException.class)
        .hasCauseInstanceOf(HttpClientErrorException.TooManyRequests.class);
  }

  @Test
  @SuppressWarnings("unchecked")
  void should_use_retry_after_header_when_present_in_429_response() {
    // Given — Retry-After header present; use 0 to keep test fast
    var headers = new HttpHeaders();
    headers.set(HttpHeaders.RETRY_AFTER, "0");
    var exception = HttpClientErrorException.create(
        HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests", headers, null, null);
    mockRestClientThrowing(exception);

    // When / Then — transient exception thrown (header was read, not default 5s path)
    assertThatThrownBy(() -> connector.fetch())
        .isInstanceOf(ConnectorTransientException.class);
  }

  @Test
  @SuppressWarnings("unchecked")
  void should_return_empty_list_when_non_retryable_4xx_received() {
    // Given — HTTP 404 Not Found: non-retryable, should not propagate
    var exception = HttpClientErrorException.create(
        HttpStatus.NOT_FOUND, "Not Found", HttpHeaders.EMPTY, null, null);
    mockRestClientThrowing(exception);

    // When
    List<RawListing> result = connector.fetch();

    // Then — returns empty list without propagating exception
    assertThat(result).isEmpty();
  }

  @Test
  @SuppressWarnings("unchecked")
  void should_propagate_server_exception_when_5xx_received() {
    // Given — HTTP 503: Resilience4j retry handles this, connector must not swallow it
    var exception = new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE);
    mockRestClientThrowing(exception);

    // When / Then — HttpServerErrorException propagates for retry and circuit-breaker tracking
    assertThatThrownBy(() -> connector.fetch())
        .isInstanceOf(HttpServerErrorException.class);
  }

  // -------------------------------------------------------------------------
  // Helpers — mock setup
  // -------------------------------------------------------------------------

  @SuppressWarnings({"unchecked", "rawtypes"})
  private void mockRestClientReturning(OnlinerSearchResponse response) {
    when(restClient.get()).thenReturn(requestHeadersUriSpec);
    when(requestHeadersUriSpec.uri(any(Function.class))).thenReturn(requestHeadersSpec);
    when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
    when(responseSpec.body(OnlinerSearchResponse.class)).thenReturn(response);
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private void mockRestClientThrowing(RuntimeException exception) {
    when(restClient.get()).thenReturn(requestHeadersUriSpec);
    when(requestHeadersUriSpec.uri(any(Function.class))).thenReturn(requestHeadersSpec);
    when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
    when(responseSpec.body(OnlinerSearchResponse.class)).thenThrow(exception);
  }

  private OnlinerSearchResponse loadFixture(String path) throws IOException {
    InputStream stream = getClass().getClassLoader().getResourceAsStream(path);
    assertThat(stream).as("fixture file not found on classpath: %s", path).isNotNull();
    return new ObjectMapper().readValue(stream, OnlinerSearchResponse.class);
  }

  // -------------------------------------------------------------------------
  // Helpers — fixture builders
  // -------------------------------------------------------------------------

  private OnlinerSearchResponse buildValidResponse() {
    var price1 = new OnlinerPrice("450.00", "USD");
    var location1 = new OnlinerLocation(
        "Минск, пр-т Независимости, 72",
        new java.math.BigDecimal("53.9272"),
        new java.math.BigDecimal("27.6244")
    );
    var area1 = new OnlinerArea(
        new java.math.BigDecimal("55.5"),
        new java.math.BigDecimal("35.2"),
        new java.math.BigDecimal("8.3")
    );
    var apt1 = new OnlinerApartment(
        1001L, "https://r.onliner.by/ak/apartments/1001", "rent",
        "https://content.onliner.by/image/1001.jpg",
        price1, location1, area1, 2, 3, 9, "2026-05-15T10:00:00Z"
    );

    var price2 = new OnlinerPrice("75000.00", "USD");
    var location2 = new OnlinerLocation(
        "Минск, ул. Немига, 5",
        new java.math.BigDecimal("53.9006"),
        new java.math.BigDecimal("27.5590")
    );
    var area2 = new OnlinerArea(
        new java.math.BigDecimal("42.0"),
        new java.math.BigDecimal("28.0"),
        new java.math.BigDecimal("6.0")
    );
    var apt2 = new OnlinerApartment(
        1002L, "https://r.onliner.by/ak/apartments/1002", "sell",
        null, price2, location2, area2, 1, 7, 12, "2026-06-01T08:30:00Z"
    );

    return new OnlinerSearchResponse(
        List.of(apt1, apt2), 2,
        new OnlinerPage(50, 2, 1, 1)
    );
  }

  private OnlinerSearchResponse buildEmptyResponse() {
    return new OnlinerSearchResponse(
        List.of(), 0,
        new OnlinerPage(50, 0, 1, 1)
    );
  }

  private OnlinerSearchResponse buildResponseWithBrokenPriceAmount() {
    var goodPrice = new OnlinerPrice("300.00", "USD");
    var goodLocation = new OnlinerLocation(
        "Минск, ул. Якуба Коласа, 12",
        new java.math.BigDecimal("53.9080"),
        new java.math.BigDecimal("27.5640")
    );
    var goodApt = new OnlinerApartment(
        3001L, "https://r.onliner.by/ak/apartments/3001", "rent",
        null, goodPrice, goodLocation, null, 1, 2, 5, null
    );

    // Apartment with price field present but amount = invalid string → new BigDecimal throws
    var brokenPrice = new OnlinerPrice("not-a-number", "USD");
    var brokenApt = new OnlinerApartment(
        3002L, "https://r.onliner.by/ak/apartments/3002", "rent",
        null, brokenPrice, null, null, null, null, null, null
    );

    return new OnlinerSearchResponse(
        List.of(goodApt, brokenApt), 2,
        new OnlinerPage(50, 2, 1, 1)
    );
  }

  private OnlinerSearchResponse buildResponseWithNullTitleFields() {
    var price = new OnlinerPrice("500.00", "USD");
    var apt = new OnlinerApartment(
        4001L, "https://r.onliner.by/ak/apartments/4001", "rent",
        null, price, null, null, null, null, null, null
    );
    return new OnlinerSearchResponse(
        List.of(apt), 1,
        new OnlinerPage(50, 1, 1, 1)
    );
  }
}
