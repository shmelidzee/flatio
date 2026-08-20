package com.flatio.integration.realt.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flatio.integration.core.ConnectorTransientException;
import com.flatio.integration.core.RawListing;
import com.flatio.integration.realt.config.RealtProperties;
import com.flatio.service.CurrencyRateService;
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
import java.util.Optional;
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

  @Mock
  private CurrencyRateService currencyRateService;

  private RealtConnector connector;

  @BeforeEach
  void setUp() {
    var properties = new RealtProperties(
        "https://realt.by",
        "REALT",
        "BY",
        "/rent/flat-for-long/",
        "/rent-flat-for-long/object/"
    );
    var htmlParser = new RealtHtmlParser(new ObjectMapper(), currencyRateService);
    connector = new RealtConnector(restClient, properties, htmlParser);
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
    assertThat(result.get(0).price()).isEqualByComparingTo(new BigDecimal("2500"));
    assertThat(result.get(0).priceUsd()).isNull();
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
    assertThat(first.title()).isEqualTo("Снять 2-комнатную квартиру г. Минск, пр-т Независимости, 72");
    assertThat(first.dealType()).isEqualTo("RENT");
    assertThat(first.propertyType()).isEqualTo("APARTMENT");
    assertThat(first.currency()).isEqualTo("USD");
    assertThat(first.price()).isEqualByComparingTo(new BigDecimal("2500"));
    assertThat(first.priceUsd()).isNull();
    assertThat(first.address()).isEqualTo("г. Минск, пр-т Независимости, 72");
    assertThat(first.sourceUrl()).isEqualTo("https://realt.by/rent-flat-for-long/object/12345678/");
    assertThat(first.photoUrls()).hasSize(1);
    assertThat(first.photoUrls().get(0)).isEqualTo("https://cdn.realt.by/img/12/3456789.jpg");
    assertThat(first.rooms()).isEqualTo(2);
    assertThat(first.floorNumber()).isEqualTo(5);
    assertThat(first.floorsTotal()).isEqualTo(9);
    assertThat(first.areaTotalM2()).isEqualByComparingTo(new BigDecimal("58"));
    assertThat(first.city()).isEqualTo("Минск");
    assertThat(first.isOwner()).isTrue();
    assertThat(first.publishedAt()).isNotNull();
  }

  @Test
  void should_set_currency_to_usd_and_price_usd_to_null_when_price_currency_is_840() throws IOException {
    // Given — realt.by stores prices in USD (priceCurrency=840, ISO 4217: 840=USD)
    String html = loadFixture("fixtures/realt/valid-listing-page.html");
    mockRestClientReturning(html);

    // When
    List<RawListing> result = connector.fetch();

    // Then — currency = USD, priceUsd = null (price field already holds USD; BYN equivalent
    // is not available at parse time and must not be fabricated from the same value)
    for (RawListing listing : result) {
      assertThat(listing.currency()).isEqualTo("USD");
      assertThat(listing.priceUsd()).isNull();
    }
  }

  @Test
  void should_parse_usd_price_with_null_price_usd_from_dedicated_fixture() throws IOException {
    // Given — dedicated fixture: one listing, priceCurrency=840 (USD), price=650
    String html = loadFixture("fixtures/realt/listing-with-usd-price.html");
    mockRestClientReturning(html);

    // When
    List<RawListing> result = connector.fetch();

    // Then — currency stored as USD, price not duplicated into priceUsd
    assertThat(result).hasSize(1);
    RawListing listing = result.get(0);
    assertThat(listing.externalId()).isEqualTo("99001122");
    assertThat(listing.currency()).isEqualTo("USD");
    assertThat(listing.price()).isEqualByComparingTo(new BigDecimal("650"));
    assertThat(listing.priceUsd()).isNull();
  }

  @Test
  void should_return_empty_photo_list_when_listing_has_no_images() throws IOException {
    // Given — second listing in fixture has empty images array
    String html = loadFixture("fixtures/realt/valid-listing-page.html");
    mockRestClientReturning(html);

    // When
    List<RawListing> result = connector.fetch();

    // Then — second listing has no photos, must return empty list (not null)
    assertThat(result.get(1).photoUrls()).isEmpty();
  }

  @Test
  void should_use_fallback_title_when_both_title_and_headline_are_null() throws IOException {
    // Given — second listing in fixture has null title and null headline
    String html = loadFixture("fixtures/realt/valid-listing-page.html");
    mockRestClientReturning(html);

    // When
    List<RawListing> result = connector.fetch();

    // Then — falls back to FALLBACK_TITLE constant
    assertThat(result.get(1).title()).isEqualTo("Квартира на Realt.by");
  }

  // -------------------------------------------------------------------------
  // BYN price computation (computePriceByn)
  // -------------------------------------------------------------------------

  @Test
  void should_compute_price_byn_when_usd_rate_is_available() {
    // Given — rate service returns 2.82 BYN/USD; listing price = 650 USD
    String html = buildPageWithSingleListing(
        "{\"code\":88800001,\"title\":\"Rate test\",\"price\":650,\"priceCurrency\":840,\"images\":[]}");
    when(currencyRateService.getUsdToByn()).thenReturn(Optional.of(new BigDecimal("2.82")));
    mockRestClientReturning(html);

    // When
    List<RawListing> result = connector.fetch();

    // Then — priceByn = 650 * 2.82 = 1833.00
    assertThat(result).hasSize(1);
    assertThat(result.get(0).priceByn()).isNotNull();
    assertThat(result.get(0).priceByn()).isEqualByComparingTo(new BigDecimal("1833.00"));
  }

  @Test
  void should_set_price_byn_to_null_when_rate_service_returns_empty() {
    // Given — rate unavailable (e.g. NBRb API down)
    String html = buildPageWithSingleListing(
        "{\"code\":88800002,\"title\":\"No rate\",\"price\":650,\"priceCurrency\":840,\"images\":[]}");
    when(currencyRateService.getUsdToByn()).thenReturn(Optional.empty());
    mockRestClientReturning(html);

    // When
    List<RawListing> result = connector.fetch();

    // Then — priceByn is null, listing is still returned without error
    assertThat(result).hasSize(1);
    assertThat(result.get(0).priceByn()).isNull();
    assertThat(result.get(0).currency()).isEqualTo("USD");
  }

  @Test
  void should_set_price_byn_to_null_when_listing_currency_is_byn() {
    // Given — listing priced in BYN (priceCurrency=933); no conversion needed
    String html = buildPageWithSingleListing(
        "{\"code\":88800003,\"title\":\"BYN listing\",\"price\":1500,\"priceCurrency\":933,\"images\":[]}");
    mockRestClientReturning(html);

    // When
    List<RawListing> result = connector.fetch();

    // Then — priceByn is null for BYN-priced listings; price is the BYN value directly
    assertThat(result).hasSize(1);
    assertThat(result.get(0).currency()).isEqualTo("BYN");
    assertThat(result.get(0).priceByn()).isNull();
  }

  // -------------------------------------------------------------------------
  // Empty responses
  // -------------------------------------------------------------------------

  @Test
  void should_return_empty_list_when_page_has_no_listing_objects() throws IOException {
    // Given — HTML page with __NEXT_DATA__ JSON containing empty objects array
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
    // Given — first listing valid (code=33333333), second has code=0 so external ID cannot be extracted
    String html = loadFixture("fixtures/realt/listing-page-with-broken-card.html");
    mockRestClientReturning(html);

    // When
    List<RawListing> result = connector.fetch();

    // Then — listing with invalid code is skipped, valid one returned
    assertThat(result).hasSize(1);
    assertThat(result.get(0).externalId()).isEqualTo("33333333");
  }

  @Test
  void should_not_throw_exception_when_single_card_is_broken() throws IOException {
    // Given — page with one broken entry (code=0) among valid ones
    String html = loadFixture("fixtures/realt/listing-page-with-broken-card.html");
    mockRestClientReturning(html);

    // When / Then — no exception propagated from the broken entry
    assertThatNoException().isThrownBy(() -> connector.fetch());
  }

  @Test
  void should_return_empty_list_when_html_has_no_next_data_script() {
    // Given — server returned HTML without __NEXT_DATA__ (no Next.js data)
    mockRestClientReturning("<html><body>Service unavailable</body></html>");

    // When
    List<RawListing> result = connector.fetch();

    // Then — no __NEXT_DATA__ found → empty list, no exception
    assertThat(result).isEmpty();
  }

  // -------------------------------------------------------------------------
  // isOwner extraction
  // -------------------------------------------------------------------------

  @Test
  void should_return_null_is_owner_when_company_uuid_field_is_absent() {
    // Given — JSON with no companyUuid field at all
    String html = buildPageWithSingleListing(
        "{\"code\":55555555,\"title\":\"Test\",\"price\":500,\"priceCurrency\":840,\"images\":[]}");
    mockRestClientReturning(html);

    // When
    List<RawListing> result = connector.fetch();

    // Then — MissingNode → isOwner must be null (field not present in response)
    assertThat(result).hasSize(1);
    assertThat(result.get(0).isOwner()).isNull();
  }

  @Test
  void should_return_false_is_owner_when_company_uuid_is_present() {
    // Given — listing belongs to an agency (companyUuid is a non-null UUID string)
    String html = buildPageWithSingleListing(
        "{\"code\":66666666,\"title\":\"Agency flat\",\"price\":600,\"priceCurrency\":840,"
            + "\"companyUuid\":\"abc-123-def-456\",\"images\":[]}");
    mockRestClientReturning(html);

    // When
    List<RawListing> result = connector.fetch();

    // Then — companyUuid present and non-null → isOwner = false (agency listing)
    assertThat(result).hasSize(1);
    assertThat(result.get(0).isOwner()).isFalse();
  }

  // -------------------------------------------------------------------------
  // Photo URL security — SSRF guard
  // -------------------------------------------------------------------------

  @Test
  void should_filter_out_non_https_photo_urls() {
    // Given — listing images array with one safe and three unsafe URLs
    String html = buildPageWithSingleListing(
        "{\"code\":77777777,\"title\":\"Test\",\"price\":500,\"priceCurrency\":840,"
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
  // Size limit guard — OOM protection
  // -------------------------------------------------------------------------

  @Test
  void should_return_empty_list_when_next_data_exceeds_size_limit() {
    // Given — __NEXT_DATA__ payload larger than MAX_NEXT_DATA_SIZE (5 MB)
    String oversizedJson = "{\"props\":{\"pageProps\":{\"objects\":[{\"x\":\""
        + "a".repeat(6 * 1024 * 1024)
        + "\"}]}}}";
    String html = "<html><body><script id=\"__NEXT_DATA__\" type=\"application/json\">"
        + oversizedJson + "</script></body></html>";
    mockRestClientReturning(html);

    // When
    List<RawListing> result = connector.fetch();

    // Then — oversized payload rejected before parsing, empty result, no exception
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
    // Given — valid-listing-page.html has no data-testid="nextBtn" link
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
    // Given — first page has data-testid="nextBtn" link, second page is empty (no more results)
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
  void should_return_partial_result_when_429_received_after_first_page_already_collected() throws IOException {
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

    // When — no exception propagates, unlike the empty-result case above
    List<RawListing> result = connector.fetch();

    // Then — page 1's listing is kept instead of being discarded by a full retry
    assertThat(result).hasSize(1);
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

  private String buildPageWithSingleListing(String listingJson) {
    return "<html><body><script id=\"__NEXT_DATA__\" type=\"application/json\">"
        + "{\"props\":{\"pageProps\":{\"objects\":[" + listingJson + "]}}}"
        + "</script></body></html>";
  }

  private String loadFixture(String path) throws IOException {
    InputStream stream = getClass().getClassLoader().getResourceAsStream(path);
    assertThat(stream).as("fixture file not found on classpath: %s", path).isNotNull();
    return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
  }
}
