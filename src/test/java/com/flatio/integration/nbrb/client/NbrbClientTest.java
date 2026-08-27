package com.flatio.integration.nbrb.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NbrbClientTest {

  private static final int USD_ID = 431;

  @Mock
  private RestClient restClient;

  @SuppressWarnings("rawtypes")
  @Mock
  private RestClient.RequestHeadersUriSpec uriSpec;

  @Mock
  private RestClient.RequestHeadersSpec headersSpec;

  @Mock
  private RestClient.ResponseSpec responseSpec;

  private NbrbClient nbrbClient;

  @BeforeEach
  void setUp() {
    nbrbClient = new NbrbClient(restClient, new ObjectMapper());
  }

  @SuppressWarnings("unchecked")
  private void givenHttpResponseBody(String body) {
    when(restClient.get()).thenReturn(uriSpec);
    doReturn(headersSpec).when(uriSpec).uri(anyString(), eq(USD_ID));
    when(headersSpec.retrieve()).thenReturn(responseSpec);
    when(responseSpec.body(String.class)).thenReturn(body);
  }

  @Test
  void should_return_rate_when_valid_response_provided() {
    // Given
    givenHttpResponseBody("{\"Cur_OfficialRate\": 3.25, \"Cur_Scale\": 1}");

    // When
    Optional<BigDecimal> result = nbrbClient.fetchRate(USD_ID);

    // Then
    assertThat(result).isPresent();
    assertThat(result.get()).isEqualByComparingTo("3.25");
  }

  @Test
  void should_normalize_rate_when_scale_is_not_one() {
    // Given — some currencies are quoted per 100 units
    givenHttpResponseBody("{\"Cur_OfficialRate\": 325, \"Cur_Scale\": 100}");

    // When
    Optional<BigDecimal> result = nbrbClient.fetchRate(USD_ID);

    // Then
    assertThat(result).isPresent();
    assertThat(result.get()).isEqualByComparingTo("3.25");
  }

  @Test
  void should_return_empty_when_rate_is_zero() {
    // Given
    givenHttpResponseBody("{\"Cur_OfficialRate\": 0, \"Cur_Scale\": 1}");

    // When
    Optional<BigDecimal> result = nbrbClient.fetchRate(USD_ID);

    // Then
    assertThat(result).isEmpty();
  }

  @Test
  void should_return_empty_when_response_is_malformed() {
    // Given
    givenHttpResponseBody("not json at all");

    // When
    Optional<BigDecimal> result = nbrbClient.fetchRate(USD_ID);

    // Then
    assertThat(result).isEmpty();
  }

  @Test
  void should_return_empty_when_expected_fields_are_missing() {
    // Given
    givenHttpResponseBody("{\"unrelated\": \"value\"}");

    // When
    Optional<BigDecimal> result = nbrbClient.fetchRate(USD_ID);

    // Then
    assertThat(result).isEmpty();
  }

  @Test
  void should_return_empty_when_fallback_invoked_after_retries_exhausted() {
    // Given / When
    Optional<BigDecimal> result = nbrbClient.fetchRateFallback(USD_ID, new RuntimeException("nbrb unreachable"));

    // Then
    assertThat(result).isEmpty();
  }
}
