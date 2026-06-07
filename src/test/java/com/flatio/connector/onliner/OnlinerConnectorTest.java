package com.flatio.connector.onliner;

import com.flatio.connector.core.RawListing;
import com.flatio.connector.onliner.dto.OnlinerSearchResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
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
  // Helpers — mock setup
  // -------------------------------------------------------------------------

  @SuppressWarnings({"unchecked", "rawtypes"})
  private void mockRestClientReturning(OnlinerSearchResponse response) {
    when(restClient.get()).thenReturn(requestHeadersUriSpec);
    when(requestHeadersUriSpec.uri(any(Function.class))).thenReturn(requestHeadersSpec);
    when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
    when(responseSpec.body(OnlinerSearchResponse.class)).thenReturn(response);
  }

  // -------------------------------------------------------------------------
  // Helpers — fixture builders
  // -------------------------------------------------------------------------

  private OnlinerSearchResponse buildValidResponse() {
    var price1 = new com.flatio.connector.onliner.dto.OnlinerPrice("450.00", "USD");
    var location1 = new com.flatio.connector.onliner.dto.OnlinerLocation(
        "Минск, пр-т Независимости, 72",
        new java.math.BigDecimal("53.9272"),
        new java.math.BigDecimal("27.6244")
    );
    var area1 = new com.flatio.connector.onliner.dto.OnlinerArea(
        new java.math.BigDecimal("55.5"),
        new java.math.BigDecimal("35.2"),
        new java.math.BigDecimal("8.3")
    );
    var apt1 = new com.flatio.connector.onliner.dto.OnlinerApartment(
        1001L, "https://r.onliner.by/ak/apartments/1001", "rent",
        "https://content.onliner.by/image/1001.jpg",
        price1, location1, area1, 2, 3, 9, "2026-05-15T10:00:00Z"
    );

    var price2 = new com.flatio.connector.onliner.dto.OnlinerPrice("75000.00", "USD");
    var location2 = new com.flatio.connector.onliner.dto.OnlinerLocation(
        "Минск, ул. Немига, 5",
        new java.math.BigDecimal("53.9006"),
        new java.math.BigDecimal("27.5590")
    );
    var area2 = new com.flatio.connector.onliner.dto.OnlinerArea(
        new java.math.BigDecimal("42.0"),
        new java.math.BigDecimal("28.0"),
        new java.math.BigDecimal("6.0")
    );
    var apt2 = new com.flatio.connector.onliner.dto.OnlinerApartment(
        1002L, "https://r.onliner.by/ak/apartments/1002", "sell",
        null, price2, location2, area2, 1, 7, 12, "2026-06-01T08:30:00Z"
    );

    return new com.flatio.connector.onliner.dto.OnlinerSearchResponse(
        List.of(apt1, apt2), 2,
        new com.flatio.connector.onliner.dto.OnlinerPage(50, 2, 1, 1)
    );
  }

  private OnlinerSearchResponse buildEmptyResponse() {
    return new com.flatio.connector.onliner.dto.OnlinerSearchResponse(
        List.of(), 0,
        new com.flatio.connector.onliner.dto.OnlinerPage(50, 0, 1, 1)
    );
  }

  private OnlinerSearchResponse buildResponseWithBrokenPriceAmount() {
    var goodPrice = new com.flatio.connector.onliner.dto.OnlinerPrice("300.00", "USD");
    var goodLocation = new com.flatio.connector.onliner.dto.OnlinerLocation(
        "Минск, ул. Якуба Коласа, 12",
        new java.math.BigDecimal("53.9080"),
        new java.math.BigDecimal("27.5640")
    );
    var goodApt = new com.flatio.connector.onliner.dto.OnlinerApartment(
        3001L, "https://r.onliner.by/ak/apartments/3001", "rent",
        null, goodPrice, goodLocation, null, 1, 2, 5, null
    );

    // Apartment with price field present but amount = invalid string → new BigDecimal throws
    var brokenPrice = new com.flatio.connector.onliner.dto.OnlinerPrice("not-a-number", "USD");
    var brokenApt = new com.flatio.connector.onliner.dto.OnlinerApartment(
        3002L, "https://r.onliner.by/ak/apartments/3002", "rent",
        null, brokenPrice, null, null, null, null, null, null
    );

    return new com.flatio.connector.onliner.dto.OnlinerSearchResponse(
        List.of(goodApt, brokenApt), 2,
        new com.flatio.connector.onliner.dto.OnlinerPage(50, 2, 1, 1)
    );
  }

  private OnlinerSearchResponse buildResponseWithNullTitleFields() {
    var price = new com.flatio.connector.onliner.dto.OnlinerPrice("500.00", "USD");
    var apt = new com.flatio.connector.onliner.dto.OnlinerApartment(
        4001L, "https://r.onliner.by/ak/apartments/4001", "rent",
        null, price, null, null, null, null, null, null
    );
    return new com.flatio.connector.onliner.dto.OnlinerSearchResponse(
        List.of(apt), 1,
        new com.flatio.connector.onliner.dto.OnlinerPage(50, 1, 1, 1)
    );
  }
}
