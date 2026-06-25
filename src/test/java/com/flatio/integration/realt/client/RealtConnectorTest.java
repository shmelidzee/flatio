package com.flatio.integration.realt.client;

import com.flatio.integration.core.ConnectorTransientException;
import com.flatio.integration.core.RawListing;
import com.flatio.integration.realt.config.RealtProperties;
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

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RealtConnectorTest {

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

  private RealtConnector connector;

  @BeforeEach
  void setUp() {
    var properties = new RealtProperties(
        "https://realt.by",
        "REALT",
        "BY",
        "/rent/flat-for-long/"
    );
    connector = new RealtConnector(restClient, properties);
  }

  // -------------------------------------------------------------------------
  // Happy path
  // -------------------------------------------------------------------------

  @Test
  void should_return_listings_when_valid_html_fixture_provided() throws IOException {
    // Given
    String html = loadFixture("fixtures/realt/valid-listing-page.html");
    mockRestClientReturning(html);

    // When
    List<RawListing> result = connector.fetch();

    // Then
    assertThat(result).hasSize(2);
    assertThat(result.get(0).externalId()).isEqualTo("12345678");
    assertThat(result.get(0).dealType()).isEqualTo("RENT");
    assertThat(result.get(0).currency()).isEqualTo("USD");
    assertThat(result.get(0).price()).isEqualByComparingTo(new BigDecimal("650"));
    assertThat(result.get(0).priceUsd()).isEqualByComparingTo(new BigDecimal("650"));
    assertThat(result.get(1).externalId()).isEqualTo("87654321");
  }

  @Test
  void should_return_source_id_and_region_from_properties() {
    // When / Then — values come from config, not hardcoded
    assertThat(connector.getSourceId()).isEqualTo("REALT");
    assertThat(connector.getSupportedRegionCode()).isEqualTo("BY");
  }

  // -------------------------------------------------------------------------
  // Field mapping
  // -------------------------------------------------------------------------

  @Test
  void should_map_all_required_fields_when_valid_card_parsed() throws IOException {
    // Given
    String html = loadFixture("fixtures/realt/valid-listing-page.html");
    mockRestClientReturning(html);

    // When
    List<RawListing> result = connector.fetch();
    RawListing first = result.get(0);

    // Then
    assertThat(first.externalId()).isEqualTo("12345678");
    assertThat(first.title()).isEqualTo("2-комнатная квартира, 58 м², 5/9 эт.");
    assertThat(first.dealType()).isEqualTo("RENT");
    assertThat(first.propertyType()).isEqualTo("APARTMENT");
    assertThat(first.currency()).isEqualTo("USD");
    assertThat(first.price()).isEqualByComparingTo(new BigDecimal("650"));
    assertThat(first.priceUsd()).isEqualByComparingTo(new BigDecimal("650"));
    assertThat(first.address()).isEqualTo("г. Минск, пр-т Независимости, 72");
    assertThat(first.sourceUrl()).isEqualTo("https://realt.by/rent/flat-for-long/object/12345678/");
    assertThat(first.photoUrls()).hasSize(1);
    assertThat(first.photoUrls().get(0)).isEqualTo("https://photo.realt.by/12/3456789.jpg");
  }

  @Test
  void should_set_price_usd_equal_to_price_when_source_currency_is_usd() throws IOException {
    // Given — realt.by lists prices in USD; price and priceUsd must be identical
    String html = loadFixture("fixtures/realt/valid-listing-page.html");
    mockRestClientReturning(html);

    // When
    List<RawListing> result = connector.fetch();

    // Then — for every listing: currency = USD, priceUsd = price
    for (RawListing listing : result) {
      assertThat(listing.currency()).isEqualTo("USD");
      assertThat(listing.priceUsd()).isEqualByComparingTo(listing.price());
    }
  }

  @Test
  void should_return_empty_photo_list_when_card_has_no_photo() throws IOException {
    // Given — second listing in fixture has no photo img
    String html = loadFixture("fixtures/realt/valid-listing-page.html");
    mockRestClientReturning(html);

    // When
    List<RawListing> result = connector.fetch();

    // Then — second listing has no photo, must return empty list (not null)
    assertThat(result.get(1).photoUrls()).isEmpty();
  }

  // -------------------------------------------------------------------------
  // Empty responses
  // -------------------------------------------------------------------------

  @Test
  void should_return_empty_list_when_page_has_no_listing_cards() throws IOException {
    // Given — HTML page without article.classified elements
    String html = loadFixture("fixtures/realt/empty-listing-page.html");
    mockRestClientReturning(html);

    // When
    List<RawListing> result = connector.fetch();

    // Then
    assertThat(result).isEmpty();
  }

  @Test
  void should_return_empty_list_when_response_is_null() {
    // Given
    mockRestClientReturning(null);

    // When
    List<RawListing> result = connector.fetch();

    // Then — graceful degradation, no exception
    assertThat(result).isEmpty();
  }

  @Test
  void should_return_empty_list_when_response_is_blank_string() {
    // Given — server returned empty body
    mockRestClientReturning("   ");

    // When
    List<RawListing> result = connector.fetch();

    // Then — treated as empty page
    assertThat(result).isEmpty();
  }

  // -------------------------------------------------------------------------
  // Error isolation — broken cards
  // -------------------------------------------------------------------------

  @Test
  void should_skip_listing_without_price_and_return_valid_ones() throws IOException {
    // Given — first listing valid, second has no price element
    String html = loadFixture("fixtures/realt/listing-page-without-price.html");
    mockRestClientReturning(html);

    // When
    List<RawListing> result = connector.fetch();

    // Then — listing without price is skipped, valid listing returned
    assertThat(result).hasSize(1);
    assertThat(result.get(0).externalId()).isEqualTo("11111111");
  }

  @Test
  void should_skip_card_without_external_id_and_return_valid_ones() throws IOException {
    // Given — first card valid, second has no data-classified-id attribute
    String html = loadFixture("fixtures/realt/listing-page-with-broken-card.html");
    mockRestClientReturning(html);

    // When
    List<RawListing> result = connector.fetch();

    // Then — card without ID is skipped, valid one returned
    assertThat(result).hasSize(1);
    assertThat(result.get(0).externalId()).isEqualTo("33333333");
  }

  @Test
  void should_not_throw_exception_when_single_card_is_broken() throws IOException {
    // Given — page with one broken card among valid ones
    String html = loadFixture("fixtures/realt/listing-page-with-broken-card.html");
    mockRestClientReturning(html);

    // When / Then — no exception propagated from the broken card
    assertThatNoException().isThrownBy(() -> connector.fetch());
  }

  @Test
  void should_return_empty_list_when_html_is_completely_broken() {
    // Given — server returned non-HTML gibberish
    mockRestClientReturning("<garbled>!!!</garbled>");

    // When
    List<RawListing> result = connector.fetch();

    // Then — Jsoup parses without throwing; no article.classified found → empty
    assertThat(result).isEmpty();
  }

  // -------------------------------------------------------------------------
  // Fallback — exhausted retries
  // -------------------------------------------------------------------------

  @Test
  void should_return_empty_list_when_fallback_is_invoked_after_exhausted_retries() {
    // Given — Resilience4j calls fallback after all retries failed
    var exception = new RestClientException("Connection refused");

    // When
    List<RawListing> result = connector.fetchFallback(exception);

    // Then — graceful degradation: empty list, no exception
    assertThat(result).isEmpty();
  }

  @Test
  void should_not_throw_from_fallback_method() {
    // Given
    var exception = new RuntimeException("Service unavailable");

    // When / Then — fallback is always safe, never throws
    assertThatNoException().isThrownBy(() -> connector.fetchFallback(exception));
  }

  // -------------------------------------------------------------------------
  // Pagination
  // -------------------------------------------------------------------------

  @Test
  void should_stop_pagination_when_no_next_page_link_in_html() throws IOException {
    // Given — valid-listing-page.html has no rel="next" link
    String html = loadFixture("fixtures/realt/valid-listing-page.html");
    mockRestClientReturning(html);

    // When
    List<RawListing> result = connector.fetch();

    // Then — only listings from the single page are returned (no infinite loop)
    assertThat(result).hasSize(2);
  }

  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void should_fetch_second_page_when_first_page_has_next_link() throws IOException {
    // Given — first page has rel="next" link, second page is empty (no more results)
    String pageWithNext = loadFixture("fixtures/realt/listing-page-with-pagination.html");
    String emptyPage = loadFixture("fixtures/realt/empty-listing-page.html");
    when(restClient.get()).thenReturn(requestHeadersUriSpec);
    when(requestHeadersUriSpec.uri(any(Function.class))).thenReturn(requestHeadersSpec);
    when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
    when(responseSpec.body(String.class)).thenReturn(pageWithNext, emptyPage);

    // When
    List<RawListing> result = connector.fetch();

    // Then — one listing from the first page; empty second page stops pagination
    assertThat(result).hasSize(1);
    assertThat(result.get(0).externalId()).isEqualTo("44444444");
  }

  // -------------------------------------------------------------------------
  // HTTP error handling
  // -------------------------------------------------------------------------

  @Test
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
  void should_return_empty_list_when_non_retryable_4xx_received() {
    // Given — HTTP 404: non-retryable, should not propagate
    var exception = HttpClientErrorException.create(
        HttpStatus.NOT_FOUND, "Not Found", HttpHeaders.EMPTY, null, null);
    mockRestClientThrowing(exception);

    // When
    List<RawListing> result = connector.fetch();

    // Then — returns empty list without propagating exception
    assertThat(result).isEmpty();
  }

  @Test
  void should_propagate_server_exception_when_5xx_received() {
    // Given — HTTP 503: Resilience4j retry handles this, connector must not swallow it
    var exception = new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE);
    mockRestClientThrowing(exception);

    // When / Then — HttpServerErrorException propagates for retry and circuit-breaker tracking
    assertThatThrownBy(() -> connector.fetch())
        .isInstanceOf(HttpServerErrorException.class);
  }

  // -------------------------------------------------------------------------
  // parseRetryAfterSeconds
  // -------------------------------------------------------------------------

  @Test
  void should_return_default_retry_after_when_headers_are_null() {
    // When
    long result = connector.parseRetryAfterSeconds(null);

    // Then — defaults to 5 seconds
    assertThat(result).isEqualTo(5L);
  }

  @Test
  void should_return_default_retry_after_when_header_is_absent() {
    // Given — headers present but no Retry-After value
    var headers = new HttpHeaders();

    // When
    long result = connector.parseRetryAfterSeconds(headers);

    // Then
    assertThat(result).isEqualTo(5L);
  }

  @Test
  void should_return_parsed_retry_after_when_header_contains_valid_seconds() {
    // Given
    var headers = new HttpHeaders();
    headers.set(HttpHeaders.RETRY_AFTER, "30");

    // When
    long result = connector.parseRetryAfterSeconds(headers);

    // Then
    assertThat(result).isEqualTo(30L);
  }

  @Test
  void should_cap_retry_after_at_60_seconds_when_header_exceeds_maximum() {
    // Given
    var headers = new HttpHeaders();
    headers.set(HttpHeaders.RETRY_AFTER, "999");

    // When
    long result = connector.parseRetryAfterSeconds(headers);

    // Then — capped at 60 to prevent excessive wait
    assertThat(result).isEqualTo(60L);
  }

  @Test
  void should_return_default_retry_after_when_header_is_not_numeric() {
    // Given — malformed Retry-After header
    var headers = new HttpHeaders();
    headers.set(HttpHeaders.RETRY_AFTER, "not-a-number");

    // When
    long result = connector.parseRetryAfterSeconds(headers);

    // Then — falls back to default 5 seconds
    assertThat(result).isEqualTo(5L);
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

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
