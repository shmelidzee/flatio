package com.flatio.integration.kufar.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KufarAdDetailClientTest {

  private static final String AD_LINK = "https://re.kufar.by/vi/1067400926";

  @Mock
  private RestClient restClient;

  @Mock
  @SuppressWarnings("rawtypes")
  private RestClient.RequestHeadersUriSpec uriSpec;

  @Mock
  @SuppressWarnings("rawtypes")
  private RestClient.RequestHeadersSpec headersSpec;

  @Mock
  private RestClient.ResponseSpec responseSpec;

  private KufarAdDetailClient adDetailClient;

  @BeforeEach
  void setUp() {
    adDetailClient = new KufarAdDetailClient(restClient, new ObjectMapper());
  }

  // -------------------------------------------------------------------------
  // Happy path
  // -------------------------------------------------------------------------

  @Test
  @SuppressWarnings("unchecked")
  void should_return_address_when_next_data_contains_valid_address() throws Exception {
    // Given — real (truncated) detail-page snapshot with a valid __NEXT_DATA__ block
    var html = loadFixture("fixtures/kufar/detail-page-valid.html");
    mockHtmlResponse(html);

    // When
    String result = adDetailClient.fetchPreciseAddress(AD_LINK);

    // Then
    assertThat(result).isEqualTo("Ленина пр, 59, Гомель, Гомельская область");
  }

  // -------------------------------------------------------------------------
  // Blank / null input — no HTTP call made
  // -------------------------------------------------------------------------

  @Test
  void should_return_null_when_ad_link_is_null() {
    // When
    String result = adDetailClient.fetchPreciseAddress(null);

    // Then
    assertThat(result).isNull();
    verify(restClient, never()).get();
  }

  @Test
  void should_return_null_when_ad_link_is_blank() {
    // When
    String result = adDetailClient.fetchPreciseAddress("   ");

    // Then
    assertThat(result).isNull();
    verify(restClient, never()).get();
  }

  // -------------------------------------------------------------------------
  // Broken / unexpected HTML and JSON — graceful degradation
  // -------------------------------------------------------------------------

  @Test
  @SuppressWarnings("unchecked")
  void should_return_null_when_next_data_marker_is_missing() {
    // Given — a page with no __NEXT_DATA__ script at all (e.g. Kufar redeployed the frontend)
    mockHtmlResponse("<html><head></head><body><div>no next data here</div></body></html>");

    // When
    String result = adDetailClient.fetchPreciseAddress(AD_LINK);

    // Then
    assertThat(result).isNull();
  }

  @Test
  @SuppressWarnings("unchecked")
  void should_return_null_when_next_data_json_is_malformed() {
    // Given — script tag present but JSON content is truncated/broken
    mockHtmlResponse("<script id=\"__NEXT_DATA__\" type=\"application/json\">{\"props\": broken</script>");

    // When / Then — no exception propagates, null returned
    assertThatNoException().isThrownBy(() -> adDetailClient.fetchPreciseAddress(AD_LINK));
    assertThat(adDetailClient.fetchPreciseAddress(AD_LINK)).isNull();
  }

  @Test
  @SuppressWarnings("unchecked")
  void should_return_null_when_address_field_is_missing_in_next_data() {
    // Given — adView.data present but without the address field (Kufar frontend shape drifted)
    var html = "<script id=\"__NEXT_DATA__\" type=\"application/json\">"
        + "{\"props\":{\"initialState\":{\"adView\":{\"data\":{\"ad_id\":1}}}}}"
        + "</script>";
    mockHtmlResponse(html);

    // When
    String result = adDetailClient.fetchPreciseAddress(AD_LINK);

    // Then
    assertThat(result).isNull();
  }

  @Test
  @SuppressWarnings("unchecked")
  void should_return_null_when_ad_view_is_missing_in_next_data() {
    // Given — an intermediate level of the expected path is entirely absent
    var html = "<script id=\"__NEXT_DATA__\" type=\"application/json\">"
        + "{\"props\":{\"initialState\":{\"user\":{\"isAuthenticated\":false}}}}"
        + "</script>";
    mockHtmlResponse(html);

    // When
    String result = adDetailClient.fetchPreciseAddress(AD_LINK);

    // Then
    assertThat(result).isNull();
  }

  @Test
  @SuppressWarnings("unchecked")
  void should_return_null_when_address_field_is_blank() {
    // Given — address key present but empty
    var html = "<script id=\"__NEXT_DATA__\" type=\"application/json\">"
        + "{\"props\":{\"initialState\":{\"adView\":{\"data\":{\"address\":\"   \"}}}}}"
        + "</script>";
    mockHtmlResponse(html);

    // When
    String result = adDetailClient.fetchPreciseAddress(AD_LINK);

    // Then
    assertThat(result).isNull();
  }

  @Test
  @SuppressWarnings("unchecked")
  void should_return_null_when_response_body_is_null() {
    // Given — server responded 200 but with an empty body
    mockHtmlResponse(null);

    // When
    String result = adDetailClient.fetchPreciseAddress(AD_LINK);

    // Then
    assertThat(result).isNull();
  }

  // -------------------------------------------------------------------------
  // Fallback method — invoked by Resilience4j @Retry after exhausted attempts
  // -------------------------------------------------------------------------

  @Test
  void should_return_null_from_fallback_when_retry_is_exhausted() {
    // Given — simulates Resilience4j invoking the fallback after all retry attempts failed
    var exception = HttpServerErrorException.create(
        HttpStatus.SERVICE_UNAVAILABLE, "Service Unavailable", null, null, null);

    // When
    String result = adDetailClient.fetchPreciseAddressFallback(AD_LINK, exception);

    // Then — degrades to null, never propagates the exception
    assertThat(result).isNull();
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  @SuppressWarnings({"unchecked", "rawtypes"})
  private void mockHtmlResponse(String html) {
    when(restClient.get()).thenReturn(uriSpec);
    doReturn(headersSpec).when(uriSpec).uri(anyString());
    when(headersSpec.retrieve()).thenReturn(responseSpec);
    when(responseSpec.body(String.class)).thenReturn(html);
  }

  private String loadFixture(String classpathPath) throws Exception {
    var resource = getClass().getClassLoader().getResource(classpathPath);
    assertThat(resource).as("fixture file not found on classpath: %s", classpathPath).isNotNull();
    return Files.readString(Path.of(resource.toURI()), StandardCharsets.UTF_8);
  }
}
