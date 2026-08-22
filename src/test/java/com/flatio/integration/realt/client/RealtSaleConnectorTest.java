package com.flatio.integration.realt.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flatio.integration.core.ConnectorTransientException;
import com.flatio.integration.core.RawListing;
import com.flatio.integration.realt.config.RealtSaleProperties;
import com.flatio.service.CurrencyRateService;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RealtSaleConnectorTest {

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

  private RealtSaleConnector connector;

  @BeforeEach
  void setUp() {
    var properties = new RealtSaleProperties(
        "https://realt.by",
        "REALT_SALE",
        "BY",
        "/sale/flats/",
        "/sale-flat/object/"
    );
    var htmlParser = new RealtHtmlParser(new ObjectMapper(), currencyRateService);
    connector = new RealtSaleConnector(restClient, properties, htmlParser);
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
    assertThat(result.get(0).dealType()).isEqualTo("SELL");
    assertThat(result.get(0).currency()).isEqualTo("USD");
    assertThat(result.get(0).price()).isEqualByComparingTo(new BigDecimal("2500"));
  }

  @Test
  void should_return_source_id_and_region_from_properties() {
    // When / Then — values come from config, not hardcoded
    assertThat(connector.getSourceId()).isEqualTo("REALT_SALE");
    assertThat(connector.getSupportedRegionCode()).isEqualTo("BY");
  }

  // -------------------------------------------------------------------------
  // Field mapping — SELL deal type
  // -------------------------------------------------------------------------

  @Test
  void should_set_deal_type_to_sell_for_all_listings() throws IOException {
    // Given
    String html = loadFixture("fixtures/realt/valid-listing-page.html");
    mockRestClientReturning(html);

    // When
    List<RawListing> result = connector.fetch();

    // Then — all listings must carry dealType=SELL (core difference from RealtConnector)
    assertThat(result).isNotEmpty();
    assertThat(result).allSatisfy(listing ->
        assertThat(listing.dealType()).isEqualTo("SELL"));
  }

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
    assertThat(first.dealType()).isEqualTo("SELL");
    assertThat(first.propertyType()).isEqualTo("APARTMENT");
    assertThat(first.currency()).isEqualTo("USD");
    assertThat(first.price()).isEqualByComparingTo(new BigDecimal("2500"));
    assertThat(first.priceUsd()).isNull();
    assertThat(first.address()).isEqualTo("г. Минск, пр-т Независимости, 72");
    assertThat(first.sourceUrl()).isEqualTo("https://realt.by/sale-flat/object/12345678/");
    assertThat(first.photoUrls()).hasSize(1);
    assertThat(first.rooms()).isEqualTo(2);
    assertThat(first.city()).isEqualTo("Минск");
    assertThat(first.publishedAt()).isNotNull();
  }

  @Test
  void should_use_sale_object_path_prefix_in_source_url() throws IOException {
    // Given
    String html = loadFixture("fixtures/realt/valid-listing-page.html");
    mockRestClientReturning(html);

    // When
    List<RawListing> result = connector.fetch();

    // Then — source URL uses sale path prefix, not the rent one
    assertThat(result.get(0).sourceUrl()).startsWith("https://realt.by/sale-flat/object/");
  }

  // -------------------------------------------------------------------------
  // BYN price computation
  // -------------------------------------------------------------------------

  @Test
  void should_compute_price_byn_when_usd_rate_is_available() {
    // Given — rate service returns 3.25 BYN/USD; listing price = 80000 USD
    String html = buildPageWithSingleListing(
        "{\"code\":88800001,\"title\":\"Sale test\",\"price\":80000,\"priceCurrency\":840,\"images\":[]}");
    when(currencyRateService.getUsdToByn()).thenReturn(Optional.of(new BigDecimal("3.25")));
    mockRestClientReturning(html);

    // When
    List<RawListing> result = connector.fetch();

    // Then — priceByn = 80000 * 3.25 = 260000.00
    assertThat(result).hasSize(1);
    assertThat(result.get(0).priceByn()).isEqualByComparingTo(new BigDecimal("260000.00"));
  }

  @Test
  void should_set_price_byn_to_null_when_rate_service_returns_empty() {
    // Given — rate unavailable
    String html = buildPageWithSingleListing(
        "{\"code\":88800002,\"title\":\"No rate\",\"price\":75000,\"priceCurrency\":840,\"images\":[]}");
    when(currencyRateService.getUsdToByn()).thenReturn(Optional.empty());
    mockRestClientReturning(html);

    // When
    List<RawListing> result = connector.fetch();

    // Then — priceByn is null; listing is still returned
    assertThat(result).hasSize(1);
    assertThat(result.get(0).priceByn()).isNull();
    assertThat(result.get(0).currency()).isEqualTo("USD");
  }

  // -------------------------------------------------------------------------
  // Empty responses
  // -------------------------------------------------------------------------

  @Test
  void should_return_empty_list_when_page_has_no_listing_objects() throws IOException {
    // Given
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

    // Then
    assertThat(result).isEmpty();
  }

  @Test
  void should_return_empty_list_when_response_is_blank_string() {
    // Given
    mockRestClientReturning("   ");

    // When
    List<RawListing> result = connector.fetch();

    // Then
    assertThat(result).isEmpty();
  }

  // -------------------------------------------------------------------------
  // Error isolation — broken cards
  // -------------------------------------------------------------------------

  @Test
  void should_return_negotiable_listing_when_price_is_zero() throws IOException {
    // Given — fixture has 11111111 (valid) and 22222222 (price=0 → negotiable)
    String html = loadFixture("fixtures/realt/listing-page-without-price.html");
    mockRestClientReturning(html);

    // When
    List<RawListing> result = connector.fetch();

    // Then — zero-price listing returned as negotiable; valid one returned normally
    assertThat(result).hasSize(2);
    assertThat(result.get(0).externalId()).isEqualTo("11111111");
    assertThat(result.get(0).isNegotiable()).isFalse();
    assertThat(result.get(1).externalId()).isEqualTo("22222222");
    assertThat(result.get(1).isNegotiable()).isTrue();
  }

  @Test
  void should_skip_card_without_external_id_and_return_valid_ones() throws IOException {
    // Given — second card has code=0, cannot extract externalId
    String html = loadFixture("fixtures/realt/listing-page-with-broken-card.html");
    mockRestClientReturning(html);

    // When
    List<RawListing> result = connector.fetch();

    // Then — broken card skipped, valid one returned
    assertThat(result).hasSize(1);
    assertThat(result.get(0).externalId()).isEqualTo("33333333");
  }

  @Test
  void should_not_throw_exception_when_single_card_is_broken() throws IOException {
    // Given
    String html = loadFixture("fixtures/realt/listing-page-with-broken-card.html");
    mockRestClientReturning(html);

    // When / Then — no exception propagated from the broken entry
    assertThatNoException().isThrownBy(() -> connector.fetch());
  }

  @Test
  void should_return_empty_list_when_html_has_no_next_data_script() {
    // Given — HTML without __NEXT_DATA__
    mockRestClientReturning("<html><body>Service unavailable</body></html>");

    // When
    List<RawListing> result = connector.fetch();

    // Then — empty list, no exception
    assertThat(result).isEmpty();
  }

  // -------------------------------------------------------------------------
  // Photo URL security — SSRF guard
  // -------------------------------------------------------------------------

  @Test
  void should_filter_out_non_https_photo_urls() {
    // Given — listing images array with one safe and three unsafe URLs
    String html = buildPageWithSingleListing(
        "{\"code\":77777777,\"title\":\"Test\",\"price\":50000,\"priceCurrency\":840,"
            + "\"companyUuid\":null,"
            + "\"images\":["
            + "  \"https://cdn.realt.by/img/valid.jpg\","
            + "  \"http://cdn.realt.by/img/insecure.jpg\","
            + "  \"javascript:alert(1)\","
            + "  \"data:image/png;base64,abc\""
            + "]}");
    mockRestClientReturning(html);

    // When
    List<RawListing> result = connector.fetch();

    // Then — only the https URL passes the safe-image check
    assertThat(result).hasSize(1);
    assertThat(result.get(0).photoUrls()).containsExactly("https://cdn.realt.by/img/valid.jpg");
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
  void should_not_throw_from_delta_fallback_method() {
    // Given
    var exception = new RuntimeException("Service unavailable");

    // When / Then — delta fallback is always safe, never throws
    assertThatNoException().isThrownBy(
        () -> connector.fetchDeltaFallback(java.time.Instant.now(), exception));
  }

  // -------------------------------------------------------------------------
  // Pagination
  // -------------------------------------------------------------------------

  @Test
  void should_stop_pagination_when_no_next_page_link_in_html() throws IOException {
    // Given — valid-listing-page.html has no data-testid="nextBtn" link
    String html = loadFixture("fixtures/realt/valid-listing-page.html");
    mockRestClientReturning(html);

    // When
    List<RawListing> result = connector.fetch();

    // Then — only listings from the single page are returned
    assertThat(result).hasSize(2);
  }

  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void should_fetch_second_page_when_first_page_has_next_link() throws IOException {
    // Given — first page has nextBtn link, second page is empty
    String pageWithNext = loadFixture("fixtures/realt/listing-page-with-pagination.html");
    String emptyPage = loadFixture("fixtures/realt/empty-listing-page.html");
    when(restClient.get()).thenReturn(requestHeadersUriSpec);
    when(requestHeadersUriSpec.uri(any(Function.class))).thenReturn(requestHeadersSpec);
    when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
    when(responseSpec.body(String.class)).thenReturn(pageWithNext, emptyPage);

    // When
    List<RawListing> result = connector.fetch();

    // Then — one listing from the first page; pagination stops on empty second page
    assertThat(result).hasSize(1);
    assertThat(result.get(0).externalId()).isEqualTo("44444444");
  }

  // -------------------------------------------------------------------------
  // HTTP error handling
  // -------------------------------------------------------------------------

  @Test
  void should_throw_connector_transient_exception_when_429_received() {
    // Given
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
  void should_return_empty_list_when_non_retryable_4xx_received() {
    // Given — HTTP 404: non-retryable
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
    // Given — HTTP 503: Resilience4j retry handles this
    var exception = new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE);
    mockRestClientThrowing(exception);

    // When / Then — propagates for retry and circuit-breaker tracking
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
  void should_cap_retry_after_at_60_seconds_when_header_exceeds_maximum() {
    // Given
    var headers = new HttpHeaders();
    headers.set(HttpHeaders.RETRY_AFTER, "999");

    // When
    long result = connector.parseRetryAfterSeconds(headers);

    // Then
    assertThat(result).isEqualTo(60L);
  }

  // -------------------------------------------------------------------------
  // fetchDelta — happy path
  // -------------------------------------------------------------------------

  @Test
  void should_return_listing_when_published_after_since_timestamp() {
    // Given — listing published on 2026-01-10, since = 2026-01-09
    String html = buildPageWithSingleListing(
        "{\"code\":99900001,\"title\":\"New Sale\",\"price\":100000,\"priceCurrency\":840,"
            + "\"createdAt\":\"2026-01-10T12:00:00+03:00\",\"images\":[]}");
    mockRestClientReturning(html);
    Instant since = Instant.parse("2026-01-09T00:00:00Z");

    // When
    List<RawListing> result = connector.fetchDelta(since);

    // Then — listing is newer than `since`, included in result
    assertThat(result).hasSize(1);
    assertThat(result.get(0).externalId()).isEqualTo("99900001");
    assertThat(result.get(0).dealType()).isEqualTo("SELL");
  }

  @Test
  void should_exclude_listing_and_stop_when_published_before_since() {
    // Given — listing published on 2026-01-01, since = 2026-01-05
    String html = buildPageWithSingleListing(
        "{\"code\":99900002,\"title\":\"Old Sale\",\"price\":100000,\"priceCurrency\":840,"
            + "\"createdAt\":\"2026-01-01T12:00:00+03:00\",\"images\":[]}");
    mockRestClientReturning(html);
    Instant since = Instant.parse("2026-01-05T00:00:00Z");

    // When
    List<RawListing> result = connector.fetchDelta(since);

    // Then — listing is older than `since`, pagination stops, nothing returned
    assertThat(result).isEmpty();
  }

  @Test
  void should_include_listing_when_published_at_is_null_in_delta_fetch() {
    // Given — createdAt field absent, publishedAt will be null
    String html = buildPageWithSingleListing(
        "{\"code\":99900003,\"title\":\"No Date Sale\",\"price\":80000,\"priceCurrency\":840,"
            + "\"images\":[]}");
    mockRestClientReturning(html);
    Instant since = Instant.parse("2026-01-05T00:00:00Z");

    // When
    List<RawListing> result = connector.fetchDelta(since);

    // Then — null publishedAt is treated as "unknown, include it"
    assertThat(result).hasSize(1);
    assertThat(result.get(0).externalId()).isEqualTo("99900003");
  }

  @Test
  void should_throw_connector_transient_exception_when_429_received_during_delta_fetch() {
    // Given
    var retryHeaders = new HttpHeaders();
    retryHeaders.set(HttpHeaders.RETRY_AFTER, "10");
    var exception = HttpClientErrorException.create(
        HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests", retryHeaders, null, null);
    mockRestClientThrowing(exception);
    Instant since = Instant.parse("2026-01-09T00:00:00Z");

    // When / Then — 429 becomes ConnectorTransientException for Resilience4j retry
    assertThatThrownBy(() -> connector.fetchDelta(since))
        .isInstanceOf(ConnectorTransientException.class)
        .hasCauseInstanceOf(HttpClientErrorException.TooManyRequests.class);
  }

  @Test
  void should_return_empty_list_when_non_retryable_4xx_received_during_delta_fetch() {
    // Given — HTTP 404 during delta: non-retryable
    var exception = HttpClientErrorException.create(
        HttpStatus.NOT_FOUND, "Not Found", HttpHeaders.EMPTY, null, null);
    mockRestClientThrowing(exception);
    Instant since = Instant.parse("2026-01-09T00:00:00Z");

    // When
    List<RawListing> result = connector.fetchDelta(since);

    // Then — returns empty list without propagating exception
    assertThat(result).isEmpty();
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

  private String buildPageWithSingleListing(String listingJson) {
    return "<html><body><script id=\"__NEXT_DATA__\" type=\"application/json\">"
        + "{\"props\":{\"pageProps\":{\"objects\":[" + listingJson + "]}}}"
        + "</script></body></html>";
  }

  private String loadFixture(String path) throws IOException {
    InputStream stream = getClass().getClassLoader().getResourceAsStream(path);
    assertThat(stream).as("fixture file not found on classpath: %s", path).isNotNull();
    return new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
  }
}
