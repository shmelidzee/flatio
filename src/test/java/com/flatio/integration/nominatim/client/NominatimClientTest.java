package com.flatio.integration.nominatim.client;

import com.flatio.integration.nominatim.config.NominatimProperties;
import com.flatio.integration.nominatim.dto.NominatimReverseResponse;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.DefaultUriBuilderFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NominatimClientTest {

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

  private NominatimClient nominatimClient;

  @BeforeEach
  void setUp() {
    var rateLimiterConfig = RateLimiterConfig.custom()
        .limitRefreshPeriod(Duration.ofMillis(1))
        .limitForPeriod(1_000)
        .timeoutDuration(Duration.ofSeconds(5))
        .build();
    var properties = new NominatimProperties("https://nominatim.test", "TestAgent/1.0", "ru");
    nominatimClient = new NominatimClient(restClient, RateLimiterRegistry.of(rateLimiterConfig), properties);
  }

  // -------------------------------------------------------------------------
  // City resolution — happy paths
  // -------------------------------------------------------------------------

  @Test
  @SuppressWarnings("unchecked")
  void should_return_city_when_nominatim_returns_city() {
    // Given
    var address = new NominatimReverseResponse.NominatimAddress("Минск", null, null, null, null, null);
    mockApiResponse(new NominatimReverseResponse("Минск, Беларусь", address));

    // When
    var result = nominatimClient.reverseGeocode(new BigDecimal("53.900"), new BigDecimal("27.567"));

    // Then
    assertThat(result).isPresent().contains("Минск");
  }

  @Test
  @SuppressWarnings("unchecked")
  void should_return_town_when_city_is_null_but_town_is_present() {
    // Given
    var address = new NominatimReverseResponse.NominatimAddress(null, "Дзержинск", null, null, null, null);
    mockApiResponse(new NominatimReverseResponse("Дзержинск, Беларусь", address));

    // When
    var result = nominatimClient.reverseGeocode(new BigDecimal("53.700"), new BigDecimal("27.100"));

    // Then
    assertThat(result).isPresent().contains("Дзержинск");
  }

  @Test
  @SuppressWarnings("unchecked")
  void should_return_village_with_county_when_nominatim_returns_village() {
    // Given
    var address = new NominatimReverseResponse.NominatimAddress(null, null, "Копище", null, "Минский район", null);
    mockApiResponse(new NominatimReverseResponse("Копище, Минский район", address));

    // When
    var result = nominatimClient.reverseGeocode(new BigDecimal("53.800"), new BigDecimal("27.700"));

    // Then
    assertThat(result).isPresent().contains("д. Копище, Минский район");
  }

  @Test
  @SuppressWarnings("unchecked")
  void should_return_village_without_county_when_county_is_null() {
    // Given
    var address = new NominatimReverseResponse.NominatimAddress(null, null, "Ратомка", null, null, null);
    mockApiResponse(new NominatimReverseResponse("Ратомка", address));

    // When
    var result = nominatimClient.reverseGeocode(new BigDecimal("54.000"), new BigDecimal("27.300"));

    // Then
    assertThat(result).isPresent();
    assertThat(result.get()).isEqualTo("д. Ратомка");
  }

  @Test
  @SuppressWarnings("unchecked")
  void should_return_hamlet_with_county_when_village_is_null() {
    // Given
    var address = new NominatimReverseResponse.NominatimAddress(null, null, null, "Тарасово", "Минский район", null);
    mockApiResponse(new NominatimReverseResponse("Тарасово, Минский район", address));

    // When
    var result = nominatimClient.reverseGeocode(new BigDecimal("53.950"), new BigDecimal("27.350"));

    // Then
    assertThat(result).isPresent().contains("д. Тарасово, Минский район");
  }

  // -------------------------------------------------------------------------
  // Empty / null response handling
  // -------------------------------------------------------------------------

  @Test
  @SuppressWarnings("unchecked")
  void should_return_empty_when_address_is_null() {
    // Given
    mockApiResponse(new NominatimReverseResponse(null, null));

    // When
    var result = nominatimClient.reverseGeocode(new BigDecimal("0.000"), new BigDecimal("0.000"));

    // Then
    assertThat(result).isEmpty();
  }

  @Test
  @SuppressWarnings("unchecked")
  void should_return_empty_when_response_is_null() {
    // Given
    mockApiResponse(null);

    // When
    var result = nominatimClient.reverseGeocode(new BigDecimal("90.000"), new BigDecimal("180.000"));

    // Then
    assertThat(result).isEmpty();
  }

  @Test
  @SuppressWarnings("unchecked")
  void should_return_empty_when_all_address_fields_are_null() {
    // Given
    var address = new NominatimReverseResponse.NominatimAddress(null, null, null, null, null, null);
    mockApiResponse(new NominatimReverseResponse("Somewhere", address));

    // When
    var result = nominatimClient.reverseGeocode(new BigDecimal("55.000"), new BigDecimal("28.000"));

    // Then
    assertThat(result).isEmpty();
  }

  // -------------------------------------------------------------------------
  // accept-language parameter
  // -------------------------------------------------------------------------

  @Test
  @SuppressWarnings("unchecked")
  void should_include_accept_language_in_request_uri() {
    // Given
    var address = new NominatimReverseResponse.NominatimAddress("Минск", null, null, null, null, null);
    var uriCaptor = ArgumentCaptor.forClass(Function.class);
    when(restClient.get()).thenReturn(requestHeadersUriSpec);
    when(requestHeadersUriSpec.uri(uriCaptor.capture())).thenReturn(requestHeadersSpec);
    when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
    when(responseSpec.body(NominatimReverseResponse.class))
        .thenReturn(new NominatimReverseResponse("Минск, Беларусь", address));

    // When
    nominatimClient.reverseGeocode(new BigDecimal("53.900"), new BigDecimal("27.567"));

    // Then — URI must contain accept-language=ru
    Function<org.springframework.web.util.UriBuilder, URI> uriFunction = uriCaptor.getValue();
    URI uri = uriFunction.apply(new DefaultUriBuilderFactory().builder());
    assertThat(uri.getQuery()).contains("accept-language=ru");
  }

  // -------------------------------------------------------------------------
  // Cache behaviour
  // -------------------------------------------------------------------------

  @Test
  @SuppressWarnings("unchecked")
  void should_return_cached_result_on_repeated_call_with_same_coordinates() {
    // Given
    var address = new NominatimReverseResponse.NominatimAddress("Минск", null, null, null, null, null);
    mockApiResponse(new NominatimReverseResponse("Минск, Беларусь", address));
    var lat = new BigDecimal("53.900");
    var lon = new BigDecimal("27.567");

    // When — first call populates cache; second call uses it
    nominatimClient.reverseGeocode(lat, lon);
    var result = nominatimClient.reverseGeocode(lat, lon);

    // Then — API invoked only once despite two calls
    assertThat(result).isPresent().contains("Минск");
    verify(restClient, times(1)).get();
  }

  @Test
  @SuppressWarnings("unchecked")
  void should_cache_empty_result_to_avoid_repeated_api_calls_for_unknown_location() {
    // Given — API returns no usable city
    var address = new NominatimReverseResponse.NominatimAddress(null, null, null, null, null, null);
    mockApiResponse(new NominatimReverseResponse("Middle of nowhere", address));
    var lat = new BigDecimal("10.000");
    var lon = new BigDecimal("10.000");

    // When
    nominatimClient.reverseGeocode(lat, lon);
    nominatimClient.reverseGeocode(lat, lon);

    // Then — API called only once; empty result also cached
    verify(restClient, times(1)).get();
  }

  @SuppressWarnings("unchecked")
  private void mockApiResponse(NominatimReverseResponse response) {
    when(restClient.get()).thenReturn(requestHeadersUriSpec);
    when(requestHeadersUriSpec.uri(any(Function.class))).thenReturn(requestHeadersSpec);
    when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
    when(responseSpec.body(NominatimReverseResponse.class)).thenReturn(response);
  }
}
