package com.flatio.integration.onliner.client;

import com.flatio.domain.listing.Listing;
import com.flatio.integration.core.ConnectorTransientException;
import com.flatio.integration.core.RawListing;
import com.flatio.integration.core.RawListingMapper;
import com.flatio.integration.core.RawListingMapperImpl;
import com.flatio.integration.onliner.config.OnlinerSaleProperties;
import com.flatio.integration.onliner.dto.OnlinerConvertedPrice;
import com.flatio.integration.onliner.dto.OnlinerLocation;
import com.flatio.integration.onliner.dto.OnlinerPage;
import com.flatio.integration.onliner.dto.OnlinerPrice;
import com.flatio.integration.onliner.dto.OnlinerSaleApartment;
import com.flatio.integration.onliner.dto.OnlinerSaleArea;
import com.flatio.integration.onliner.dto.OnlinerSaleSearchResponse;
import com.flatio.integration.onliner.dto.OnlinerSaleSeller;
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
class OnlinerSaleConnectorTest {

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

  private OnlinerSaleConnector connector;

  @BeforeEach
  void setUp() {
    var properties = new OnlinerSaleProperties(
        "https://pk.api.onliner.by",
        "ONLINER_SALE",
        "BY",
        "/search/apartments",
        50
    );
    connector = new OnlinerSaleConnector(restClient, properties);
  }

  // -------------------------------------------------------------------------
  // Happy path
  // -------------------------------------------------------------------------

  @Test
  @SuppressWarnings("unchecked")
  void should_return_sale_listings_when_valid_response_provided() {
    // Given
    var response = buildValidResponse();
    mockRestClientReturning(response);

    // When
    List<RawListing> result = connector.fetch();

    // Then
    assertThat(result).hasSize(2);
    assertThat(result.get(0).externalId()).isEqualTo("3001");
    assertThat(result.get(0).dealType()).isEqualTo("SELL");
    assertThat(result.get(0).currency()).isEqualTo("BYN");
    assertThat(result.get(1).externalId()).isEqualTo("3002");
    assertThat(result.get(1).dealType()).isEqualTo("SELL");
  }

  @Test
  void should_return_source_id_and_region_from_properties() {
    // When / Then — values come from config, not hardcoded
    assertThat(connector.getSourceId()).isEqualTo("ONLINER_SALE");
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

    // Then
    assertThat(result).isEmpty();
  }

  // -------------------------------------------------------------------------
  // Fallback methods
  // -------------------------------------------------------------------------

  @Test
  void should_return_empty_list_when_fetch_fallback_invoked() {
    // Given
    var exception = new RestClientException("503 Service Unavailable");

    // When
    List<RawListing> result = connector.fetchFallback(exception);

    // Then
    assertThat(result).isEmpty();
  }

  @Test
  void should_not_throw_from_fetch_fallback_method() {
    // When / Then
    assertThatNoException().isThrownBy(
        () -> connector.fetchFallback(new RestClientException("Connection refused")));
  }

  @Test
  void should_return_empty_list_when_fetchAll_fallback_invoked() {
    // Given
    var exception = new RestClientException("timeout");

    // When
    List<RawListing> result = connector.fetchAllFallback(exception);

    // Then
    assertThat(result).isEmpty();
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
    assertThat(first.externalId()).isEqualTo("3001");
    assertThat(first.dealType()).isEqualTo("SELL");
    assertThat(first.propertyType()).isEqualTo("APARTMENT");
    assertThat(first.sourceUrl()).isEqualTo("https://r.onliner.by/pk/apartments/3001");
    assertThat(first.price()).isEqualByComparingTo("203573.00");
    assertThat(first.currency()).isEqualTo("BYN");
    assertThat(first.priceUsd()).isEqualByComparingTo("73449.63");
    assertThat(first.rooms()).isEqualTo(2);
    assertThat(first.address()).isEqualTo("Минск, Орловская ул. 11");
    assertThat(first.city()).isEqualTo("Минск");
    assertThat(first.latitude()).isNotNull();
    assertThat(first.longitude()).isNotNull();
    assertThat(first.publishedAt()).isNotNull();
  }

  @Test
  @SuppressWarnings("unchecked")
  void should_map_number_of_rooms_directly_as_integer() {
    // Given — sale API provides number_of_rooms as integer, not rent_type
    var response = buildResponseWithRooms(3);
    mockRestClientReturning(response);

    // When
    List<RawListing> result = connector.fetch();

    // Then
    assertThat(result.get(0).rooms()).isEqualTo(3);
  }

  @Test
  @SuppressWarnings("unchecked")
  void should_return_null_rooms_when_number_of_rooms_is_null() {
    // Given
    var response = buildResponseWithRooms(null);
    mockRestClientReturning(response);

    // When
    List<RawListing> result = connector.fetch();

    // Then
    assertThat(result.get(0).rooms()).isNull();
  }

  // -------------------------------------------------------------------------
  // Owner mapping — seller.type → isOwner
  // -------------------------------------------------------------------------

  @Test
  @SuppressWarnings("unchecked")
  void should_map_seller_type_owner_to_is_owner_true() {
    // Given — seller.type = "owner" (private seller, not an agent)
    var response = buildResponseWithSellerType("owner");
    mockRestClientReturning(response);

    // When
    List<RawListing> result = connector.fetch();

    // Then
    assertThat(result.get(0).isOwner()).isTrue();
  }

  @Test
  @SuppressWarnings("unchecked")
  void should_map_seller_type_agent_to_is_owner_false() {
    // Given — seller.type = "agent" (realtor)
    var response = buildResponseWithSellerType("agent");
    mockRestClientReturning(response);

    // When
    List<RawListing> result = connector.fetch();

    // Then
    assertThat(result.get(0).isOwner()).isFalse();
  }

  @Test
  @SuppressWarnings("unchecked")
  void should_return_null_is_owner_when_seller_is_absent() {
    // Given — seller field absent in API response
    var response = buildResponseWithNullSeller();
    mockRestClientReturning(response);

    // When
    List<RawListing> result = connector.fetch();

    // Then — null signals unknown ownership, not false
    assertThat(result.get(0).isOwner()).isNull();
  }

  // -------------------------------------------------------------------------
  // Error handling and isolation
  // -------------------------------------------------------------------------

  @Test
  @SuppressWarnings("unchecked")
  void should_skip_listing_with_missing_byn_price_and_return_others() {
    // Given — one apartment has no BYN converted price
    var response = buildResponseWithMissingBynPrice();
    mockRestClientReturning(response);

    // When
    List<RawListing> result = connector.fetch();

    // Then — broken listing is skipped, the valid one is returned
    assertThat(result).hasSize(1);
    assertThat(result.get(0).externalId()).isEqualTo("4001");
  }

  @Test
  @SuppressWarnings("unchecked")
  void should_not_propagate_exception_when_single_listing_is_broken() {
    // Given — response with one broken listing among valid ones
    var response = buildResponseWithMissingBynPrice();
    mockRestClientReturning(response);

    // When / Then — no exception propagated
    assertThatNoException().isThrownBy(() -> connector.fetch());
  }

  @Test
  @SuppressWarnings("unchecked")
  void should_return_fallback_title_when_address_is_null() {
    // Given
    var response = buildResponseWithNullLocation();
    mockRestClientReturning(response);

    // When
    List<RawListing> result = connector.fetch();

    // Then
    assertThat(result).hasSize(1);
    assertThat(result.get(0).title()).isEqualTo("Квартира на продажу (Onliner)");
  }

  @Test
  @SuppressWarnings("unchecked")
  void should_return_empty_photo_list_when_photo_is_null() {
    // Given
    var response = buildResponseWithNullPhoto();
    mockRestClientReturning(response);

    // When
    List<RawListing> result = connector.fetch();

    // Then
    assertThat(result.get(0).photoUrls()).isEmpty();
  }

  // -------------------------------------------------------------------------
  // imgproxy URL decoding
  // -------------------------------------------------------------------------

  @Test
  @SuppressWarnings("unchecked")
  void should_decode_imgproxy_url_for_sale_listing_photo() {
    // Given — same imgproxy format used in both rent and sale APIs
    String originalUrl = "https://files.realt.by/img/62/2e53e872-55d0-11f1-90ff-0242ac120003.jpg";
    String base64 = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(originalUrl.getBytes(StandardCharsets.UTF_8));
    String imgproxyUrl = "https://imgproxy.onliner.by/sig/w:600/h:400/" + base64;
    var response = buildResponseWithPhoto(imgproxyUrl);
    mockRestClientReturning(response);

    // When
    List<RawListing> result = connector.fetch();

    // Then
    assertThat(result).hasSize(1);
    assertThat(result.get(0).photoUrls()).hasSize(1);
    assertThat(result.get(0).photoUrls().get(0)).isEqualTo(originalUrl);
  }

  @Test
  @SuppressWarnings("unchecked")
  void should_decode_imgproxy_url_when_base64_is_split_across_multiple_segments() {
    // Given — production imgproxy format splits base64 into multiple path segments
    String imgproxyUrl = "https://imgproxy.onliner.by/0puYR9vckLlrzZwN1PXltnbosDD3yntcpDZm7ZTLTfo"
        + "/w:600/h:400"
        + "/aHR0cHM6Ly9maWxl"
        + "/cy5yZWFsdC5ieS9p"
        + "/bWcvNjIvMmU1M2U4"
        + "/NzItNTVkMC0xMWYx"
        + "/LTkwZmYtMDI0MmFj"
        + "/MTIwMDAzLmpwZw";
    var response = buildResponseWithPhoto(imgproxyUrl);
    mockRestClientReturning(response);

    // When
    List<RawListing> result = connector.fetch();

    // Then
    assertThat(result).hasSize(1);
    assertThat(result.get(0).photoUrls()).hasSize(1);
    assertThat(result.get(0).photoUrls().get(0))
        .isEqualTo("https://files.realt.by/img/62/2e53e872-55d0-11f1-90ff-0242ac120003.jpg");
  }

  @Test
  @SuppressWarnings("unchecked")
  void should_use_photo_url_as_is_when_not_imgproxy() {
    // Given
    String directUrl = "https://content.onliner.by/image/sale_2001.jpg";
    var response = buildResponseWithPhoto(directUrl);
    mockRestClientReturning(response);

    // When
    List<RawListing> result = connector.fetch();

    // Then
    assertThat(result.get(0).photoUrls()).hasSize(1);
    assertThat(result.get(0).photoUrls().get(0)).isEqualTo(directUrl);
  }

  // -------------------------------------------------------------------------
  // City extraction from address (#350)
  // -------------------------------------------------------------------------

  @Test
  @SuppressWarnings("unchecked")
  void should_extract_city_from_address_when_address_has_street_segment() {
    // Given
    var response = buildResponseWithAddress("Минск, улица Кедышко, 3");
    mockRestClientReturning(response);

    // When
    List<RawListing> result = connector.fetch();

    // Then
    assertThat(result).hasSize(1);
    assertThat(result.get(0).city()).isEqualTo("Минск");
  }

  @Test
  @SuppressWarnings("unchecked")
  void should_return_whole_address_as_city_when_address_has_no_comma() {
    // Given — some Onliner listings only carry a bare city name, no street
    var response = buildResponseWithAddress("Полоцк");
    mockRestClientReturning(response);

    // When
    List<RawListing> result = connector.fetch();

    // Then
    assertThat(result.get(0).city()).isEqualTo("Полоцк");
  }

  @Test
  @SuppressWarnings("unchecked")
  void should_return_null_city_when_address_is_null() {
    // Given — apartment with location = null
    var response = buildResponseWithNullLocation();
    mockRestClientReturning(response);

    // When
    List<RawListing> result = connector.fetch();

    // Then
    assertThat(result.get(0).city()).isNull();
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

    // When / Then
    assertThatThrownBy(() -> connector.fetch())
        .isInstanceOf(ConnectorTransientException.class)
        .hasCauseInstanceOf(HttpClientErrorException.TooManyRequests.class);
  }

  @Test
  void should_cap_retry_after_when_server_returns_large_value() {
    // Given — server returns suspiciously large value to block the scheduler thread
    var headers = new HttpHeaders();
    headers.set(HttpHeaders.RETRY_AFTER, "9999");

    // When
    long result = connector.parseRetryAfterSeconds(headers);

    // Then — capped at 60 seconds
    assertThat(result).isEqualTo(60L);
  }

  @Test
  @SuppressWarnings("unchecked")
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
  @SuppressWarnings("unchecked")
  void should_propagate_server_exception_when_5xx_received() {
    // Given
    var exception = new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE);
    mockRestClientThrowing(exception);

    // When / Then
    assertThatThrownBy(() -> connector.fetch())
        .isInstanceOf(HttpServerErrorException.class);
  }

  // -------------------------------------------------------------------------
  // Fixture-based deserialization
  // -------------------------------------------------------------------------

  @Test
  @SuppressWarnings("unchecked")
  void should_correctly_deserialize_valid_sale_response_fixture() throws IOException {
    // Given — real-structure Onliner sale API snapshot
    var response = loadFixture("fixtures/onliner/valid-sale-response.json");
    mockRestClientReturning(response);

    // When
    List<RawListing> result = connector.fetch();

    // Then
    assertThat(result).hasSize(2);
    assertThat(result.get(0).externalId()).isEqualTo("2001");
    assertThat(result.get(0).dealType()).isEqualTo("SELL");
    assertThat(result.get(0).price()).isEqualByComparingTo("203573.00");
    assertThat(result.get(0).currency()).isEqualTo("BYN");
    assertThat(result.get(0).priceUsd()).isEqualByComparingTo("73449.63");
    assertThat(result.get(0).rooms()).isEqualTo(2);
    assertThat(result.get(0).isOwner()).isTrue();    // seller.type = "owner"
    assertThat(result.get(0).city()).isEqualTo("Минск");
    assertThat(result.get(1).externalId()).isEqualTo("2002");
    assertThat(result.get(1).rooms()).isEqualTo(3);
    assertThat(result.get(1).isOwner()).isFalse();   // seller.type = "agent"
    assertThat(result.get(1).photoUrls()).isEmpty();
    assertThat(result.get(1).city()).isEqualTo("Минск");
  }

  @Test
  @SuppressWarnings("unchecked")
  void should_persist_non_blank_address_through_full_pipeline_when_valid_sale_fixture_provided() throws IOException {
    // Given — regression test for #327 (Onliner address reported empty in the DB) for the sale
    // connector counterpart. Chains fetch -> RawListing -> RawListingMapper -> Listing in one
    // test against a realistic fixture, so a future regression at any of those three seams
    // (Jackson deserialization, toRawListing, or the MapStruct mapper) fails this test.
    var response = loadFixture("fixtures/onliner/valid-sale-response.json");
    mockRestClientReturning(response);
    RawListingMapper mapper = new RawListingMapperImpl();

    // When
    List<RawListing> rawListings = connector.fetch();
    Listing firstListing = mapper.toEntity(rawListings.get(0));
    Listing secondListing = mapper.toEntity(rawListings.get(1));

    // Then — the fixture's location.address survives fetch -> RawListing -> Listing unchanged
    assertThat(rawListings.get(0).address()).isEqualTo("Минск, Орловская ул. 11");
    assertThat(firstListing.getAddress()).isEqualTo("Минск, Орловская ул. 11");
    assertThat(rawListings.get(1).address()).isEqualTo("Минск, ул. Немига, 5");
    assertThat(secondListing.getAddress()).isEqualTo("Минск, ул. Немига, 5");
  }

  @Test
  @SuppressWarnings("unchecked")
  void should_return_empty_list_from_empty_sale_response_fixture() throws IOException {
    // Given
    var response = loadFixture("fixtures/onliner/empty-sale-response.json");
    mockRestClientReturning(response);

    // When
    List<RawListing> result = connector.fetch();

    // Then
    assertThat(result).isEmpty();
  }

  // -------------------------------------------------------------------------
  // Helpers — mock setup
  // -------------------------------------------------------------------------

  @SuppressWarnings({"unchecked", "rawtypes"})
  private void mockRestClientReturning(OnlinerSaleSearchResponse response) {
    when(restClient.get()).thenReturn(requestHeadersUriSpec);
    when(requestHeadersUriSpec.uri(any(Function.class))).thenReturn(requestHeadersSpec);
    when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
    when(responseSpec.body(OnlinerSaleSearchResponse.class)).thenReturn(response);
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private void mockRestClientThrowing(RuntimeException exception) {
    when(restClient.get()).thenReturn(requestHeadersUriSpec);
    when(requestHeadersUriSpec.uri(any(Function.class))).thenReturn(requestHeadersSpec);
    when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
    when(responseSpec.body(OnlinerSaleSearchResponse.class)).thenThrow(exception);
  }

  private OnlinerSaleSearchResponse loadFixture(String path) throws IOException {
    InputStream stream = getClass().getClassLoader().getResourceAsStream(path);
    assertThat(stream).as("fixture file not found on classpath: %s", path).isNotNull();
    ObjectMapper mapper = new ObjectMapper();
    mapper.registerModule(new JavaTimeModule());
    return mapper.readValue(stream, OnlinerSaleSearchResponse.class);
  }

  // -------------------------------------------------------------------------
  // Helpers — fixture builders
  // -------------------------------------------------------------------------

  private OnlinerSaleSearchResponse buildValidResponse() {
    var converted1 = Map.of(
        "BYN", new OnlinerConvertedPrice("203573.00", "BYN"),
        "USD", new OnlinerConvertedPrice("73449.63", "USD")
    );
    var price1 = new OnlinerPrice("73449.63", "USD", converted1);
    var location1 = new OnlinerLocation(
        "Минск, Орловская ул. 11",
        new BigDecimal("53.9319"),
        new BigDecimal("27.5629")
    );
    var apt1 = new OnlinerSaleApartment(
        3001L,
        "https://r.onliner.by/pk/apartments/3001",
        2,
        "https://content.onliner.by/image/3001.jpg",
        price1,
        location1,
        new OnlinerSaleSeller("owner"),
        new OnlinerSaleArea(55.0, 30.0, 9.0),
        true,
        OffsetDateTime.parse("2026-05-22T18:00:38+03:00"),
        OffsetDateTime.parse("2026-06-10T14:25:10+03:00")
    );

    var converted2 = Map.of(
        "BYN", new OnlinerConvertedPrice("166296.00", "BYN"),
        "USD", new OnlinerConvertedPrice("60000.00", "USD")
    );
    var price2 = new OnlinerPrice("60000.00", "USD", converted2);
    var location2 = new OnlinerLocation(
        "Минск, ул. Немига, 5",
        new BigDecimal("53.9006"),
        new BigDecimal("27.5590")
    );
    var apt2 = new OnlinerSaleApartment(
        3002L,
        "https://r.onliner.by/pk/apartments/3002",
        3,
        null,
        price2,
        location2,
        new OnlinerSaleSeller("agent"),
        new OnlinerSaleArea(85.0, 55.0, 12.0),
        false,
        OffsetDateTime.parse("2026-04-11T20:01:08+03:00"),
        OffsetDateTime.parse("2026-06-11T16:41:53+03:00")
    );

    return new OnlinerSaleSearchResponse(
        List.of(apt1, apt2), 2,
        new OnlinerPage(50, 2, 1, 1)
    );
  }

  private OnlinerSaleSearchResponse buildEmptyResponse() {
    return new OnlinerSaleSearchResponse(
        List.of(), 0,
        new OnlinerPage(50, 0, 1, 1)
    );
  }

  private OnlinerSaleSearchResponse buildResponseWithRooms(Integer rooms) {
    var price = buildStandardPrice();
    var location = buildStandardLocation();
    var apt = new OnlinerSaleApartment(
        5001L, "https://r.onliner.by/pk/apartments/5001",
        rooms, null, price, location,
        new OnlinerSaleSeller("owner"), null, true,
        OffsetDateTime.parse("2026-06-01T12:00:00+03:00"),
        OffsetDateTime.parse("2026-06-01T12:00:00+03:00")
    );
    return new OnlinerSaleSearchResponse(List.of(apt), 1, new OnlinerPage(50, 1, 1, 1));
  }

  private OnlinerSaleSearchResponse buildResponseWithSellerType(String type) {
    var price = buildStandardPrice();
    var location = buildStandardLocation();
    var apt = new OnlinerSaleApartment(
        6001L, "https://r.onliner.by/pk/apartments/6001",
        2, null, price, location,
        new OnlinerSaleSeller(type), null, true,
        OffsetDateTime.parse("2026-06-01T12:00:00+03:00"),
        OffsetDateTime.parse("2026-06-01T12:00:00+03:00")
    );
    return new OnlinerSaleSearchResponse(List.of(apt), 1, new OnlinerPage(50, 1, 1, 1));
  }

  private OnlinerSaleSearchResponse buildResponseWithNullSeller() {
    var price = buildStandardPrice();
    var location = buildStandardLocation();
    var apt = new OnlinerSaleApartment(
        7001L, "https://r.onliner.by/pk/apartments/7001",
        2, null, price, location,
        null, null, true,
        OffsetDateTime.parse("2026-06-01T12:00:00+03:00"),
        OffsetDateTime.parse("2026-06-01T12:00:00+03:00")
    );
    return new OnlinerSaleSearchResponse(List.of(apt), 1, new OnlinerPage(50, 1, 1, 1));
  }

  private OnlinerSaleSearchResponse buildResponseWithMissingBynPrice() {
    var goodPrice = buildStandardPrice();
    var goodApt = new OnlinerSaleApartment(
        4001L, "https://r.onliner.by/pk/apartments/4001",
        2, null, goodPrice, buildStandardLocation(),
        new OnlinerSaleSeller("owner"), null, true,
        OffsetDateTime.parse("2026-06-01T12:00:00+03:00"),
        OffsetDateTime.parse("2026-06-01T12:00:00+03:00")
    );
    // Price present but no BYN converted entry → toRawListing throws
    var brokenPrice = new OnlinerPrice("60000.00", "USD",
        Map.of("USD", new OnlinerConvertedPrice("60000.00", "USD")));
    var brokenApt = new OnlinerSaleApartment(
        4002L, "https://r.onliner.by/pk/apartments/4002",
        3, null, brokenPrice, buildStandardLocation(),
        new OnlinerSaleSeller("agent"), null, false,
        OffsetDateTime.parse("2026-06-01T12:00:00+03:00"),
        OffsetDateTime.parse("2026-06-01T12:00:00+03:00")
    );
    return new OnlinerSaleSearchResponse(
        List.of(goodApt, brokenApt), 2, new OnlinerPage(50, 2, 1, 1));
  }

  private OnlinerSaleSearchResponse buildResponseWithNullLocation() {
    var apt = new OnlinerSaleApartment(
        8001L, "https://r.onliner.by/pk/apartments/8001",
        2, null, buildStandardPrice(), null,
        new OnlinerSaleSeller("owner"), null, true,
        OffsetDateTime.parse("2026-06-01T12:00:00+03:00"),
        OffsetDateTime.parse("2026-06-01T12:00:00+03:00")
    );
    return new OnlinerSaleSearchResponse(List.of(apt), 1, new OnlinerPage(50, 1, 1, 1));
  }

  private OnlinerSaleSearchResponse buildResponseWithNullPhoto() {
    var apt = new OnlinerSaleApartment(
        9001L, "https://r.onliner.by/pk/apartments/9001",
        2, null, buildStandardPrice(), buildStandardLocation(),
        new OnlinerSaleSeller("owner"), null, true,
        OffsetDateTime.parse("2026-06-01T12:00:00+03:00"),
        OffsetDateTime.parse("2026-06-01T12:00:00+03:00")
    );
    return new OnlinerSaleSearchResponse(List.of(apt), 1, new OnlinerPage(50, 1, 1, 1));
  }

  private OnlinerSaleSearchResponse buildResponseWithPhoto(String photoUrl) {
    var apt = new OnlinerSaleApartment(
        9002L, "https://r.onliner.by/pk/apartments/9002",
        2, photoUrl, buildStandardPrice(), buildStandardLocation(),
        new OnlinerSaleSeller("owner"), null, true,
        OffsetDateTime.parse("2026-06-01T12:00:00+03:00"),
        OffsetDateTime.parse("2026-06-01T12:00:00+03:00")
    );
    return new OnlinerSaleSearchResponse(List.of(apt), 1, new OnlinerPage(50, 1, 1, 1));
  }

  private OnlinerSaleSearchResponse buildResponseWithAddress(String address) {
    var location = new OnlinerLocation(
        address,
        new BigDecimal("53.9040"),
        new BigDecimal("27.5620")
    );
    var apt = new OnlinerSaleApartment(
        10001L, "https://r.onliner.by/pk/apartments/10001",
        2, null, buildStandardPrice(), location,
        new OnlinerSaleSeller("owner"), null, true,
        OffsetDateTime.parse("2026-06-01T12:00:00+03:00"),
        OffsetDateTime.parse("2026-06-01T12:00:00+03:00")
    );
    return new OnlinerSaleSearchResponse(List.of(apt), 1, new OnlinerPage(50, 1, 1, 1));
  }

  private OnlinerPrice buildStandardPrice() {
    return new OnlinerPrice("73449.63", "USD", Map.of(
        "BYN", new OnlinerConvertedPrice("203573.00", "BYN"),
        "USD", new OnlinerConvertedPrice("73449.63", "USD")
    ));
  }

  private OnlinerLocation buildStandardLocation() {
    return new OnlinerLocation(
        "Минск, Орловская ул. 11",
        new BigDecimal("53.9319"),
        new BigDecimal("27.5629")
    );
  }
}
