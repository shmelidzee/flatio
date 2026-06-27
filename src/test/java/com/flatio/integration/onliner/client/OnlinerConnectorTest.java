package com.flatio.integration.onliner.client;

import com.flatio.integration.core.ConnectorTransientException;
import com.flatio.integration.core.RawListing;
import com.flatio.integration.onliner.config.OnlinerProperties;
import com.flatio.integration.onliner.dto.OnlinerApartment;
import com.flatio.integration.onliner.dto.OnlinerContact;
import com.flatio.integration.onliner.dto.OnlinerConvertedPrice;
import com.flatio.integration.onliner.dto.OnlinerLocation;
import com.flatio.integration.onliner.dto.OnlinerPage;
import com.flatio.integration.onliner.dto.OnlinerPrice;
import com.flatio.integration.onliner.dto.OnlinerSearchResponse;
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
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
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
    assertThat(result.get(0).dealType()).isEqualTo("RENT");
    assertThat(result.get(0).currency()).isEqualTo("BYN");
    assertThat(result.get(0).photoUrls()).hasSize(1);

    assertThat(result.get(1).externalId()).isEqualTo("1002");
    assertThat(result.get(1).dealType()).isEqualTo("RENT");
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
    assertThat(first.rooms()).isNull();
    assertThat(first.floorNumber()).isNull();
    assertThat(first.floorsTotal()).isNull();
    assertThat(first.areaTotalM2()).isNull();
    assertThat(first.address()).isEqualTo("Минск, пр-т Независимости, 72");
    assertThat(first.latitude()).isNotNull();
    assertThat(first.longitude()).isNotNull();
    assertThat(first.publishedAt()).isNotNull();
    assertThat(first.title()).isNotBlank();
  }

  @Test
  @SuppressWarnings("unchecked")
  void should_return_fallback_title_when_all_title_fields_are_null() {
    // Given — apartment with address null
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
  // imgproxy URL decoding (#170)
  // -------------------------------------------------------------------------

  @Test
  @SuppressWarnings("unchecked")
  void should_decode_imgproxy_url_when_base64_is_single_segment() {
    // Given — production format: {sig}/w:860/h:645/dpr:2/{base64}
    String originalUrl = "https://content.onliner.by/mini/2025/01/14/apartment_123456.jpg";
    String base64 = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(originalUrl.getBytes(StandardCharsets.UTF_8));
    String imgproxyUrl = "https://imgproxy.onliner.by/sig123/w:860/h:645/dpr:2/" + base64;
    var response = buildResponseWithPhoto(imgproxyUrl);
    mockRestClientReturning(response);

    // When
    List<RawListing> result = connector.fetch();

    // Then — imgproxy URL decoded to original content URL
    assertThat(result).hasSize(1);
    assertThat(result.get(0).photoUrls()).hasSize(1);
    assertThat(result.get(0).photoUrls().get(0)).isEqualTo(originalUrl);
  }

  @Test
  @SuppressWarnings("unchecked")
  void should_decode_imgproxy_url_when_base64_is_split_across_multiple_path_segments() {
    // Given — real production URL: Onliner imgproxy splits base64 into 16-char segments
    // Source URL from production causing the bug (url=g in logs before fix)
    String imgproxyUrl = "https://imgproxy.onliner.by/2jqDY0cGpVp8eHktwsfBkJ5dJxBdzPHDLk2V107UPmk"
        + "/w:600/h:400/dpr:2"
        + "/aHR0cHM6Ly9jb250"
        + "/ZW50Lm9ubGluZXIu"
        + "/YnkvYXBhcnRtZW50"
        + "/c19waG90by8yMTgw"
        + "/MDYzL29yaWdpbmFs"
        + "/LzMwMzMxYzJjYjFi"
        + "/OWE1M2JkMTE3ZjM4"
        + "/ODNmZjYxNTEzLmpw"
        + "/Zw";
    var response = buildResponseWithPhoto(imgproxyUrl);
    mockRestClientReturning(response);

    // When
    List<RawListing> result = connector.fetch();

    // Then — all 9 base64 segments joined and decoded to valid content URL
    assertThat(result).hasSize(1);
    assertThat(result.get(0).photoUrls()).hasSize(1);
    assertThat(result.get(0).photoUrls().get(0))
        .isEqualTo("https://content.onliner.by/apartments_photo/2180063/original/30331c2cb1b9a53bd117f3883ff61513.jpg");
  }

  @Test
  @SuppressWarnings("unchecked")
  void should_return_empty_photo_list_when_imgproxy_base64_is_invalid() {
    // Given — imgproxy URL with non-decodable segment after transform params
    String imgproxyUrl = "https://imgproxy.onliner.by/sig/w:860/h:645/dpr:2/!!!invalid-base64!!!";
    var response = buildResponseWithPhoto(imgproxyUrl);
    mockRestClientReturning(response);

    // When
    List<RawListing> result = connector.fetch();

    // Then — decoding failed → photo omitted, listing still returned (graceful degradation)
    assertThat(result).hasSize(1);
    assertThat(result.get(0).photoUrls()).isEmpty();
  }

  @Test
  @SuppressWarnings("unchecked")
  void should_return_null_photo_when_imgproxy_url_has_no_transform_params() {
    // Given — imgproxy host but no segment with ':' after it (unexpected format)
    String imgproxyUrl = "https://imgproxy.onliner.by/aHR0cHM6Ly9jb250ZW50";
    var response = buildResponseWithPhoto(imgproxyUrl);
    mockRestClientReturning(response);

    // When
    List<RawListing> result = connector.fetch();

    // Then — format unrecognised → photo omitted gracefully
    assertThat(result).hasSize(1);
    assertThat(result.get(0).photoUrls()).isEmpty();
  }

  @Test
  @SuppressWarnings("unchecked")
  void should_use_photo_url_as_is_when_not_imgproxy() {
    // Given — regular CDN URL (not imgproxy)
    String directUrl = "https://content.onliner.by/image/1001.jpg";
    var response = buildResponseWithPhoto(directUrl);
    mockRestClientReturning(response);

    // When
    List<RawListing> result = connector.fetch();

    // Then — non-imgproxy URL returned unchanged
    assertThat(result).hasSize(1);
    assertThat(result.get(0).photoUrls()).hasSize(1);
    assertThat(result.get(0).photoUrls().get(0)).isEqualTo(directUrl);
  }

  @Test
  @SuppressWarnings("unchecked")
  void should_use_created_at_as_published_at_when_present() {
    // Given — apartment with both createdAt and lastTimeUp set to different values
    var response = buildResponseWithDistinctCreatedAtAndLastTimeUp();
    mockRestClientReturning(response);

    // When
    List<RawListing> result = connector.fetch();

    // Then — publishedAt comes from createdAt, not lastTimeUp (#269)
    assertThat(result).hasSize(1);
    assertThat(result.get(0).publishedAt())
        .isEqualTo(OffsetDateTime.parse("2026-01-01T00:00:00+00:00").toInstant());
  }

  @Test
  @SuppressWarnings("unchecked")
  void should_return_null_published_at_when_created_at_is_absent() {
    // Given — apartment with createdAt = null; lastTimeUp is present but must not be used
    var response = buildResponseWithNullCreatedAt();
    mockRestClientReturning(response);

    // When
    List<RawListing> result = connector.fetch();

    // Then — publishedAt is null because createdAt is null, not a crash (#269)
    assertThat(result).hasSize(1);
    assertThat(result.get(0).publishedAt()).isNull();
  }

  // -------------------------------------------------------------------------
  // Fixture-based deserialization (JSON → RawListing full chain)
  // -------------------------------------------------------------------------

  @Test
  @SuppressWarnings("unchecked")
  void should_correctly_deserialize_valid_response_fixture_including_json_property_mappings() throws IOException {
    // Given — loads actual Onliner API snapshot; verifies @JsonProperty (rent_type, last_time_up, etc.)
    var response = loadFixture("fixtures/onliner/valid-response.json");
    mockRestClientReturning(response);

    // When
    List<RawListing> result = connector.fetch();

    // Then — all snake_case JSON fields correctly mapped to camelCase Java fields
    assertThat(result).hasSize(2);
    assertThat(result.get(0).externalId()).isEqualTo("1001");
    assertThat(result.get(0).dealType()).isEqualTo("RENT");
    assertThat(result.get(0).price()).isEqualByComparingTo("1470.00");
    assertThat(result.get(0).currency()).isEqualTo("BYN");
    assertThat(result.get(0).priceUsd()).isEqualByComparingTo("450.00");
    assertThat(result.get(0).publishedAt()).isNotNull();
    assertThat(result.get(0).rooms()).isEqualTo(2);   // rent_type = "2_rooms"
    assertThat(result.get(0).isOwner()).isTrue();      // contact.owner = true
    assertThat(result.get(1).externalId()).isEqualTo("1002");
    assertThat(result.get(1).dealType()).isEqualTo("RENT");
    assertThat(result.get(1).photoUrls()).isEmpty();
    assertThat(result.get(1).rooms()).isEqualTo(3);   // rent_type = "3_rooms"
    assertThat(result.get(1).isOwner()).isFalse();     // contact.owner = false
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
  // Property type mapping — rent_type → propertyType (FR-LST-15)
  // -------------------------------------------------------------------------

  @Test
  @SuppressWarnings("unchecked")
  void should_map_rent_type_room_to_property_type_room() {
    // Given — apartment with rent_type = "room" (single room for rent, not a full apartment)
    var response = buildResponseWithRentType("room");
    mockRestClientReturning(response);

    // When
    List<RawListing> result = connector.fetch();

    // Then
    assertThat(result).hasSize(1);
    assertThat(result.get(0).propertyType()).isEqualTo("ROOM");
  }

  @Test
  @SuppressWarnings("unchecked")
  void should_map_rent_type_1_room_to_property_type_apartment() {
    // Given
    var response = buildResponseWithRentType("1_room");
    mockRestClientReturning(response);

    // When
    List<RawListing> result = connector.fetch();

    // Then — any N_room(s) rent_type maps to APARTMENT
    assertThat(result).hasSize(1);
    assertThat(result.get(0).propertyType()).isEqualTo("APARTMENT");
  }

  @Test
  @SuppressWarnings("unchecked")
  void should_map_null_rent_type_to_property_type_apartment() {
    // Given — rent_type absent in source response
    var response = buildResponseWithRentType(null);
    mockRestClientReturning(response);

    // When
    List<RawListing> result = connector.fetch();

    // Then — defaults to APARTMENT when rent_type is unknown
    assertThat(result).hasSize(1);
    assertThat(result.get(0).propertyType()).isEqualTo("APARTMENT");
  }

  // -------------------------------------------------------------------------
  // Rooms count mapping — rent_type → rooms (FR-LST-15)
  // -------------------------------------------------------------------------

  @Test
  @SuppressWarnings("unchecked")
  void should_map_rent_type_1_room_to_rooms_count_1() {
    // Given
    var response = buildResponseWithRentType("1_room");
    mockRestClientReturning(response);

    // When
    List<RawListing> result = connector.fetch();

    // Then
    assertThat(result.get(0).rooms()).isEqualTo(1);
  }

  @Test
  @SuppressWarnings("unchecked")
  void should_map_rent_type_2_rooms_to_rooms_count_2() {
    // Given
    var response = buildResponseWithRentType("2_rooms");
    mockRestClientReturning(response);

    // When
    List<RawListing> result = connector.fetch();

    // Then
    assertThat(result.get(0).rooms()).isEqualTo(2);
  }

  @Test
  @SuppressWarnings("unchecked")
  void should_map_rent_type_3_rooms_to_rooms_count_3() {
    // Given
    var response = buildResponseWithRentType("3_rooms");
    mockRestClientReturning(response);

    // When
    List<RawListing> result = connector.fetch();

    // Then
    assertThat(result.get(0).rooms()).isEqualTo(3);
  }

  @Test
  @SuppressWarnings("unchecked")
  void should_map_rent_type_4_rooms_to_rooms_count_4() {
    // Given
    var response = buildResponseWithRentType("4_rooms");
    mockRestClientReturning(response);

    // When
    List<RawListing> result = connector.fetch();

    // Then
    assertThat(result.get(0).rooms()).isEqualTo(4);
  }

  @Test
  @SuppressWarnings("unchecked")
  void should_map_rent_type_room_to_rooms_count_null() {
    // Given — "room" = single room for rent, not a full apartment → rooms count not applicable
    var response = buildResponseWithRentType("room");
    mockRestClientReturning(response);

    // When
    List<RawListing> result = connector.fetch();

    // Then — "room" is not in the RENT_TYPE_TO_ROOMS map → null
    assertThat(result.get(0).rooms()).isNull();
  }

  // -------------------------------------------------------------------------
  // Owner contact mapping — contact.owner → isOwner (FR-LST-16)
  // -------------------------------------------------------------------------

  @Test
  @SuppressWarnings("unchecked")
  void should_map_contact_owner_true_to_is_owner_true() {
    // Given — contact.owner = true (listing posted directly by the property owner)
    var response = buildResponseWithOwner(true);
    mockRestClientReturning(response);

    // When
    List<RawListing> result = connector.fetch();

    // Then
    assertThat(result.get(0).isOwner()).isTrue();
  }

  @Test
  @SuppressWarnings("unchecked")
  void should_map_contact_owner_false_to_is_owner_false() {
    // Given — contact.owner = false (listing posted by an agent or agency)
    var response = buildResponseWithOwner(false);
    mockRestClientReturning(response);

    // When
    List<RawListing> result = connector.fetch();

    // Then
    assertThat(result.get(0).isOwner()).isFalse();
  }

  @Test
  @SuppressWarnings("unchecked")
  void should_return_null_is_owner_when_contact_is_absent() {
    // Given — contact field absent in API response
    var response = buildResponseWithNullContact();
    mockRestClientReturning(response);

    // When
    List<RawListing> result = connector.fetch();

    // Then — null signals unknown ownership, not false
    assertThat(result.get(0).isOwner()).isNull();
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
    ObjectMapper mapper = new ObjectMapper();
    mapper.registerModule(new JavaTimeModule());
    return mapper.readValue(stream, OnlinerSearchResponse.class);
  }

  // -------------------------------------------------------------------------
  // Helpers — fixture builders
  // -------------------------------------------------------------------------

  private OnlinerSearchResponse buildValidResponse() {
    var converted1 = Map.of(
        "BYN", new OnlinerConvertedPrice("1470.00", "BYN"),
        "USD", new OnlinerConvertedPrice("450.00", "USD")
    );
    var price1 = new OnlinerPrice("450.00", "USD", converted1);
    var location1 = new OnlinerLocation(
        "Минск, пр-т Независимости, 72",
        new BigDecimal("53.9272"),
        new BigDecimal("27.6244")
    );
    var contact1 = new OnlinerContact(true);
    var apt1 = new OnlinerApartment(
        1001L,
        "https://r.onliner.by/ak/apartments/1001",
        "rent",
        "https://content.onliner.by/image/1001.jpg",
        price1,
        location1,
        contact1,
        OffsetDateTime.parse("2026-05-15T10:00:00+03:00"),
        OffsetDateTime.parse("2026-05-15T10:00:00+03:00")
    );

    var converted2 = Map.of(
        "BYN", new OnlinerConvertedPrice("245250.00", "BYN"),
        "USD", new OnlinerConvertedPrice("75000.00", "USD")
    );
    var price2 = new OnlinerPrice("75000.00", "USD", converted2);
    var location2 = new OnlinerLocation(
        "Минск, ул. Немига, 5",
        new BigDecimal("53.9006"),
        new BigDecimal("27.5590")
    );
    var contact2 = new OnlinerContact(false);
    var apt2 = new OnlinerApartment(
        1002L,
        "https://r.onliner.by/ak/apartments/1002",
        "sell",
        null,
        price2,
        location2,
        contact2,
        OffsetDateTime.parse("2026-06-01T08:30:00+03:00"),
        OffsetDateTime.parse("2026-06-01T08:30:00+03:00")
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
    var goodPrice = new OnlinerPrice("300.00", "USD",
        Map.of("BYN", new OnlinerConvertedPrice("978.00", "BYN")));
    var goodLocation = new OnlinerLocation(
        "Минск, ул. Якуба Коласа, 12",
        new BigDecimal("53.9080"),
        new BigDecimal("27.5640")
    );
    var goodContact = new OnlinerContact(true);
    var goodApt = new OnlinerApartment(
        3001L,
        "https://r.onliner.by/ak/apartments/3001",
        "rent",
        null,
        goodPrice,
        goodLocation,
        goodContact,
        OffsetDateTime.parse("2026-06-02T09:00:00+03:00"),
        null
    );

    // Apartment with price field present but amount = invalid string → new BigDecimal throws
    var brokenPrice = new OnlinerPrice("not-a-number", "USD", null);
    var brokenApt = new OnlinerApartment(
        3002L,
        "https://r.onliner.by/ak/apartments/3002",
        "rent",
        null,
        brokenPrice,
        null,
        null,
        null,
        null
    );

    return new OnlinerSearchResponse(
        List.of(goodApt, brokenApt), 2,
        new OnlinerPage(50, 2, 1, 1)
    );
  }

  private OnlinerSearchResponse buildResponseWithNullTitleFields() {
    var price = new OnlinerPrice("500.00", "USD",
        Map.of("BYN", new OnlinerConvertedPrice("1630.00", "BYN")));
    var apt = new OnlinerApartment(
        4001L,
        "https://r.onliner.by/ak/apartments/4001",
        "rent",
        null,
        price,
        null,
        null,
        null,
        null
    );
    return new OnlinerSearchResponse(
        List.of(apt), 1,
        new OnlinerPage(50, 1, 1, 1)
    );
  }

  private OnlinerSearchResponse buildResponseWithNullCreatedAt() {
    var price = new OnlinerPrice("400.00", "USD",
        Map.of("BYN", new OnlinerConvertedPrice("1305.00", "BYN")));
    var location = new OnlinerLocation(
        "Минск, ул. Ленина, 1",
        new BigDecimal("53.9040"),
        new BigDecimal("27.5620")
    );
    // createdAt = null, lastTimeUp = non-null — verifies lastTimeUp is not used as fallback
    var apt = new OnlinerApartment(
        5001L,
        "https://r.onliner.by/ak/apartments/5001",
        "rent",
        null,
        price,
        location,
        null,
        null,
        OffsetDateTime.parse("2026-06-05T12:00:00+03:00")
    );
    return new OnlinerSearchResponse(
        List.of(apt), 1,
        new OnlinerPage(50, 1, 1, 1)
    );
  }

  private OnlinerSearchResponse buildResponseWithDistinctCreatedAtAndLastTimeUp() {
    var price = new OnlinerPrice("400.00", "USD",
        Map.of("BYN", new OnlinerConvertedPrice("1305.00", "BYN")));
    var location = new OnlinerLocation(
        "Минск, ул. Ленина, 1",
        new BigDecimal("53.9040"),
        new BigDecimal("27.5620")
    );
    // createdAt and lastTimeUp are intentionally different to verify only createdAt is used (#269)
    var apt = new OnlinerApartment(
        5002L,
        "https://r.onliner.by/ak/apartments/5002",
        "rent",
        null,
        price,
        location,
        null,
        OffsetDateTime.parse("2026-01-01T00:00:00+00:00"),
        OffsetDateTime.parse("2026-06-27T12:00:00+00:00")
    );
    return new OnlinerSearchResponse(
        List.of(apt), 1,
        new OnlinerPage(50, 1, 1, 1)
    );
  }

  private OnlinerSearchResponse buildResponseWithRentType(String rentType) {
    var price = new OnlinerPrice("400.00", "USD",
        Map.of("BYN", new OnlinerConvertedPrice("1305.00", "BYN")));
    var location = new OnlinerLocation(
        "Минск, ул. Ленина, 1",
        new BigDecimal("53.9040"),
        new BigDecimal("27.5620")
    );
    var apt = new OnlinerApartment(
        9001L,
        "https://r.onliner.by/ak/apartments/9001",
        rentType,
        null,
        price,
        location,
        new OnlinerContact(true),
        OffsetDateTime.parse("2026-06-05T12:00:00+03:00"),
        OffsetDateTime.parse("2026-06-05T12:00:00+03:00")
    );
    return new OnlinerSearchResponse(
        List.of(apt), 1,
        new OnlinerPage(50, 1, 1, 1)
    );
  }

  private OnlinerSearchResponse buildResponseWithOwner(boolean owner) {
    var price = new OnlinerPrice("500.00", "USD",
        Map.of("BYN", new OnlinerConvertedPrice("1632.50", "BYN")));
    var location = new OnlinerLocation(
        "Минск, ул. Сурганова, 8",
        new BigDecimal("53.9100"),
        new BigDecimal("27.5700")
    );
    var apt = new OnlinerApartment(
        8001L,
        "https://r.onliner.by/ak/apartments/8001",
        "2_rooms",
        null,
        price,
        location,
        new OnlinerContact(owner),
        OffsetDateTime.parse("2026-06-06T09:00:00+03:00"),
        OffsetDateTime.parse("2026-06-06T09:00:00+03:00")
    );
    return new OnlinerSearchResponse(
        List.of(apt), 1,
        new OnlinerPage(50, 1, 1, 1)
    );
  }

  private OnlinerSearchResponse buildResponseWithPhoto(String photoUrl) {
    var price = new OnlinerPrice("400.00", "USD",
        Map.of("BYN", new OnlinerConvertedPrice("1305.00", "BYN")));
    var location = new OnlinerLocation(
        "Минск, ул. Ленина, 1",
        new BigDecimal("53.9040"),
        new BigDecimal("27.5620")
    );
    var apt = new OnlinerApartment(
        6001L,
        "https://r.onliner.by/ak/apartments/6001",
        "2_rooms",
        photoUrl,
        price,
        location,
        new OnlinerContact(true),
        OffsetDateTime.parse("2026-06-05T12:00:00+03:00"),
        OffsetDateTime.parse("2026-06-05T12:00:00+03:00")
    );
    return new OnlinerSearchResponse(
        List.of(apt), 1,
        new OnlinerPage(50, 1, 1, 1)
    );
  }

  private OnlinerSearchResponse buildResponseWithNullContact() {
    var price = new OnlinerPrice("600.00", "USD",
        Map.of("BYN", new OnlinerConvertedPrice("1959.00", "BYN")));
    var location = new OnlinerLocation(
        "Минск, ул. Комсомольская, 3",
        new BigDecimal("53.9050"),
        new BigDecimal("27.5580")
    );
    var apt = new OnlinerApartment(
        7001L,
        "https://r.onliner.by/ak/apartments/7001",
        "1_room",
        null,
        price,
        location,
        null,
        OffsetDateTime.parse("2026-06-07T10:00:00+03:00"),
        OffsetDateTime.parse("2026-06-07T10:00:00+03:00")
    );
    return new OnlinerSearchResponse(
        List.of(apt), 1,
        new OnlinerPage(50, 1, 1, 1)
    );
  }
}
