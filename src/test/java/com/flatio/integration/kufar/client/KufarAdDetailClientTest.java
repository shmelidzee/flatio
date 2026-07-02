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

  @Test
  @SuppressWarnings("unchecked")
  void should_return_null_when_next_data_script_is_truncated_before_closing_tag() {
    // Given — HTML contains the __NEXT_DATA__ marker and the opening '>' of the script tag,
    // but the response body is cut off before the closing </script> (e.g. a truncated download)
    mockHtmlResponse("<script id=\"__NEXT_DATA__\" type=\"application/json\">"
        + "{\"props\":{\"initialState\":{\"adView\":{\"data\":{\"address\":\"Ленина пр, 59\"}}}}}");

    // When / Then — no exception propagates, null returned since no closing tag was found
    assertThatNoException().isThrownBy(() -> adDetailClient.fetchPreciseAddress(AD_LINK));
    assertThat(adDetailClient.fetchPreciseAddress(AD_LINK)).isNull();
  }

  // -------------------------------------------------------------------------
  // SSRF allowlist — isAllowedKufarUrl (issue #313 Security Engineer finding)
  // -------------------------------------------------------------------------

  @Test
  @SuppressWarnings("unchecked")
  void should_perform_http_call_when_ad_link_is_https_on_kufar_by_root_host() {
    // Given — the allowlisted root host itself (no subdomain)
    mockHtmlResponse("<script id=\"__NEXT_DATA__\" type=\"application/json\">"
        + "{\"props\":{\"initialState\":{\"adView\":{\"data\":{\"address\":\"Минск\"}}}}}</script>");

    // When
    String result = adDetailClient.fetchPreciseAddress("https://kufar.by/vi/1067400926");

    // Then
    assertThat(result).isEqualTo("Минск");
  }

  @Test
  @SuppressWarnings("unchecked")
  void should_perform_http_call_when_ad_link_is_https_on_different_kufar_by_subdomain() {
    // Given — a different subdomain of kufar.by than the usual re.kufar.by
    mockHtmlResponse("<script id=\"__NEXT_DATA__\" type=\"application/json\">"
        + "{\"props\":{\"initialState\":{\"adView\":{\"data\":{\"address\":\"Минск\"}}}}}</script>");

    // When
    String result = adDetailClient.fetchPreciseAddress("https://api.kufar.by/vi/1067400926");

    // Then
    assertThat(result).isEqualTo("Минск");
  }

  @Test
  void should_return_null_when_ad_link_scheme_is_http_not_https() {
    // When — same allowlisted host, but plain http instead of https
    String result = adDetailClient.fetchPreciseAddress("http://re.kufar.by/vi/1067400926");

    // Then — rejected before any HTTP call is made
    assertThat(result).isNull();
    verify(restClient, never()).get();
  }

  @Test
  void should_return_null_when_ad_link_host_is_entirely_different_domain() {
    // When — a well-formed https URL, but on a host outside the kufar.by allowlist entirely
    String result = adDetailClient.fetchPreciseAddress("https://evil.com/vi/1067400926");

    // Then
    assertThat(result).isNull();
    verify(restClient, never()).get();
  }

  @Test
  void should_return_null_when_ad_link_host_is_domain_suffix_lookalike_attack() {
    // When — "kufar.by.evil.com" starts with the string "kufar.by" but is actually a host
    // controlled by evil.com; a naive prefix/substring check could be tricked by this, but the
    // implementation only allows exact "kufar.by" or a "*.kufar.by" *suffix*, so this must
    // still be rejected since the host does not end with ".kufar.by"
    String result = adDetailClient.fetchPreciseAddress("https://kufar.by.evil.com/vi/1067400926");

    // Then
    assertThat(result).isNull();
    verify(restClient, never()).get();
  }

  @Test
  void should_return_null_when_ad_link_host_is_lookalike_without_dot_separator() {
    // When — "evilkufar.by" ends with the literal characters "kufar.by" but is not a subdomain
    // of kufar.by (there is no dot separator). This guards against a subtly wrong allowlist
    // check such as host.endsWith("kufar.by") (missing the leading dot), which would wrongly
    // let this host through; the implementation checks host.endsWith(".kufar.by") instead
    String result = adDetailClient.fetchPreciseAddress("https://evilkufar.by/vi/1067400926");

    // Then
    assertThat(result).isNull();
    verify(restClient, never()).get();
  }

  @Test
  void should_return_null_when_ad_link_is_malformed_uri() {
    // When — not a syntactically valid URI at all (spaces, stray colon)
    String result = adDetailClient.fetchPreciseAddress("not a url with spaces and : bad chars");

    // Then — URISyntaxException is caught internally, no exception propagates
    assertThat(result).isNull();
    verify(restClient, never()).get();
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
