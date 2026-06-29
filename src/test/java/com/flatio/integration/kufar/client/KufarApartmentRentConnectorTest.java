package com.flatio.integration.kufar.client;

import com.flatio.integration.core.RawListing;
import com.flatio.integration.kufar.config.KufarProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KufarApartmentRentConnectorTest {

  @Mock
  private KufarApiClient kufarApiClient;

  private KufarApartmentRentConnector connector;
  private KufarProperties properties;

  @BeforeEach
  void setUp() {
    properties = new KufarProperties(
        "https://api.kufar.by",
        "/search-api/v2/search/rendered-paginated",
        50,
        "ru",
        "https://content.kufar.by/listings_thumbnails",
        new KufarProperties.CategoryConfig("KUFAR_APARTMENT_RENT", "BY", "1010", "let"),
        new KufarProperties.CategoryConfig("KUFAR_APARTMENT_SALE", "BY", "1010", "sell"),
        new KufarProperties.CategoryConfig("KUFAR_ROOM_RENT", "BY", "1040", "let"),
        new KufarProperties.CategoryConfig("KUFAR_ROOM_SALE", "BY", "1040", "sell"),
        new KufarProperties.CategoryConfig("KUFAR_HOUSE_RENT", "BY", "1020", "let"),
        new KufarProperties.CategoryConfig("KUFAR_HOUSE_SALE", "BY", "1020", "sell")
    );
    connector = new KufarApartmentRentConnector(kufarApiClient, properties);
  }

  // -------------------------------------------------------------------------
  // Metadata
  // -------------------------------------------------------------------------

  @Test
  void should_return_source_id_and_region_from_properties() {
    assertThat(connector.getSourceId()).isEqualTo("KUFAR_APARTMENT_RENT");
    assertThat(connector.getSupportedRegionCode()).isEqualTo("BY");
  }

  // -------------------------------------------------------------------------
  // Delegation
  // -------------------------------------------------------------------------

  @Test
  void should_delegate_fetch_to_api_client_with_rent_apartment_params() {
    // Given
    List<RawListing> expected = List.of();
    when(kufarApiClient.fetchAll(
        eq(properties.apartmentRent()), eq("RENT"), eq("APARTMENT"), any()))
        .thenReturn(expected);

    // When
    List<RawListing> result = connector.fetch();

    // Then
    assertThat(result).isSameAs(expected);
    verify(kufarApiClient).fetchAll(properties.apartmentRent(), "RENT", "APARTMENT",
        "Квартира в аренду (Kufar.by)");
  }

  @Test
  void should_delegate_fetch_delta_to_api_client() {
    // Given
    Instant since = Instant.parse("2024-01-15T10:00:00Z");
    List<RawListing> expected = List.of();
    when(kufarApiClient.fetchDelta(
        eq(properties.apartmentRent()), eq(since), eq("RENT"), eq("APARTMENT"), any()))
        .thenReturn(expected);

    // When
    List<RawListing> result = connector.fetchDelta(since);

    // Then
    assertThat(result).isSameAs(expected);
  }

  // -------------------------------------------------------------------------
  // Fallback methods
  // -------------------------------------------------------------------------

  @Test
  void should_return_empty_list_from_fetch_fallback() {
    // Given
    var exception = new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE);

    // When
    List<RawListing> result = connector.fetchFallback(exception);

    // Then
    assertThat(result).isEmpty();
  }

  @Test
  void should_return_empty_list_from_fetch_delta_fallback() {
    // Given
    Instant since = Instant.parse("2024-01-15T10:00:00Z");
    var exception = new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE);

    // When
    List<RawListing> result = connector.fetchDeltaFallback(since, exception);

    // Then
    assertThat(result).isEmpty();
  }

  @Test
  void should_not_throw_from_any_fallback() {
    // Given
    var exception = new RuntimeException("Network error");
    Instant since = Instant.now();

    // When / Then — all fallback methods are always safe
    assertThatNoException().isThrownBy(() -> connector.fetchFallback(exception));
    assertThatNoException().isThrownBy(() -> connector.fetchDeltaFallback(since, exception));
  }
}
