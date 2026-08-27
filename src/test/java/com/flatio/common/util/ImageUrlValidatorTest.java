package com.flatio.common.util;

import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class ImageUrlValidatorTest {

  private ImageUrlValidator validator;

  @BeforeEach
  void setUp() {
    validator = new ImageUrlValidator(Set.of("onliner.by", "kufar.by", "realt.by"));
  }

  // -------------------------------------------------------------------------
  // isAllowedImageUrl — matching against an explicit allowlist
  // -------------------------------------------------------------------------

  @ParameterizedTest
  @ValueSource(strings = {
      "https://onliner.by/photo.jpg",
      "https://content.onliner.by/photo.jpg",
      "https://kufar.by/photo.jpg",
      "https://img01.kufar.by/photo.jpg",
      "https://realt.by/photo.jpg",
      "https://cdn.realt.by/photo.jpg"
  })
  void should_return_true_when_url_is_https_and_host_matches_allowlist(String url) {
    // When
    boolean result = validator.isAllowedImageUrl(url);

    // Then
    assertThat(result).isTrue();
  }

  @Test
  void should_return_false_when_url_is_null() {
    // When
    boolean result = validator.isAllowedImageUrl(null);

    // Then
    assertThat(result).isFalse();
  }

  @Test
  void should_return_false_when_url_is_blank() {
    // When
    boolean result = validator.isAllowedImageUrl("   ");

    // Then
    assertThat(result).isFalse();
  }

  @Test
  void should_return_false_when_scheme_is_http() {
    // When
    boolean result = validator.isAllowedImageUrl("http://onliner.by/photo.jpg");

    // Then
    assertThat(result).isFalse();
  }

  @Test
  void should_return_false_when_host_is_not_on_allowlist() {
    // When — SSRF vector: arbitrary attacker-controlled host
    boolean result = validator.isAllowedImageUrl("https://evil.com/photo.jpg");

    // Then
    assertThat(result).isFalse();
  }

  @Test
  void should_return_false_when_host_only_contains_allowlisted_suffix_as_substring() {
    // When — SSRF vector: "onliner.by.evil.com" contains "onliner.by" as a substring, not a suffix
    boolean result = validator.isAllowedImageUrl("https://onliner.by.evil.com/photo.jpg");

    // Then
    assertThat(result).isFalse();
  }

  @Test
  void should_return_false_when_host_is_loopback_address() {
    // When — SSRF vector: loopback address
    boolean result = validator.isAllowedImageUrl("https://127.0.0.1/photo.jpg");

    // Then
    assertThat(result).isFalse();
  }

  @Test
  void should_return_false_when_host_is_cloud_metadata_address() {
    // When — SSRF vector: cloud metadata endpoint
    boolean result = validator.isAllowedImageUrl("https://169.254.169.254/latest/meta-data/");

    // Then
    assertThat(result).isFalse();
  }

  @Test
  void should_return_false_when_url_has_no_host() {
    // When
    boolean result = validator.isAllowedImageUrl("https:///photo.jpg");

    // Then
    assertThat(result).isFalse();
  }

  @Test
  void should_return_false_when_url_is_malformed() {
    // When
    boolean result = validator.isAllowedImageUrl("not a url at all ::");

    // Then
    assertThat(result).isFalse();
  }

  @Test
  void should_be_case_insensitive_for_scheme_and_host() {
    // When
    boolean result = validator.isAllowedImageUrl("HTTPS://CDN.ONLINER.BY/photo.jpg");

    // Then
    assertThat(result).isTrue();
  }

  @Test
  void should_return_false_when_allowlist_is_empty() {
    // Given — no connector config contributed any host (e.g. misconfigured environment)
    var emptyValidator = new ImageUrlValidator(Set.of());

    // When
    boolean result = emptyValidator.isAllowedImageUrl("https://onliner.by/photo.jpg");

    // Then
    assertThat(result).isFalse();
  }

  // -------------------------------------------------------------------------
  // Environment-derived allowlist (issue #424)
  // -------------------------------------------------------------------------

  @Test
  void should_derive_allowlist_from_connector_base_url_and_photo_cdn_base_url_properties() {
    // Given — mirrors the shape of application.yml's connector.* block
    var environment = new MockEnvironment();
    environment.setProperty("connector.onliner.base-url", "https://ak.api.onliner.by");
    environment.setProperty("connector.kufar.base-url", "https://api.kufar.by");
    environment.setProperty("connector.kufar.photo-cdn-base-url", "https://rms.kufar.by/v1/gallery");
    environment.setProperty("connector.realt.base-url", "https://realt.by");

    // When
    var derivedValidator = new ImageUrlValidator(environment);

    // Then — registrable domain of each configured host is allowed
    assertThat(derivedValidator.isAllowedImageUrl("https://content.onliner.by/photo.jpg")).isTrue();
    assertThat(derivedValidator.isAllowedImageUrl("https://img01.kufar.by/photo.jpg")).isTrue();
    assertThat(derivedValidator.isAllowedImageUrl("https://cdn.realt.by/photo.jpg")).isTrue();
    assertThat(derivedValidator.isAllowedImageUrl("https://evil.com/photo.jpg")).isFalse();
  }

  @Test
  void should_ignore_non_connector_url_properties_when_deriving_allowlist() {
    // Given — a base-url-shaped key outside the "connector.*" namespace must not contribute
    var environment = new MockEnvironment();
    environment.setProperty("connector.onliner.base-url", "https://ak.api.onliner.by");
    environment.setProperty("nominatim.base-url", "https://nominatim.openstreetmap.org");

    // When
    var derivedValidator = new ImageUrlValidator(environment);

    // Then
    assertThat(derivedValidator.isAllowedImageUrl("https://content.onliner.by/photo.jpg")).isTrue();
    assertThat(derivedValidator.isAllowedImageUrl("https://nominatim.openstreetmap.org/photo.jpg")).isFalse();
  }

  @Test
  void should_skip_unparsable_connector_url_when_deriving_allowlist() {
    // Given — a malformed connector URL must not crash startup, just be skipped
    var environment = new MockEnvironment();
    environment.setProperty("connector.broken.base-url", "not a url at all ::");
    environment.setProperty("connector.onliner.base-url", "https://ak.api.onliner.by");

    // When / Then — no exception, and the well-formed entry is still derived
    var derivedValidator = new ImageUrlValidator(environment);
    assertThat(derivedValidator.isAllowedImageUrl("https://content.onliner.by/photo.jpg")).isTrue();
  }

  // -------------------------------------------------------------------------
  // Loopback/private-address guard (issue #450)
  // -------------------------------------------------------------------------

  @Test
  void should_not_derive_localhost_into_allowlist_when_connector_base_url_points_there() {
    // Given — the exact scenario from issue #450: a local override (e.g. application-local.yml)
    // points a connector at a dev stub server, alongside a normal, legitimate source
    var environment = new MockEnvironment();
    environment.setProperty("connector.onliner.base-url", "http://localhost:8089");
    environment.setProperty("connector.kufar.base-url", "https://api.kufar.by");

    // When
    var derivedValidator = new ImageUrlValidator(environment);

    // Then — localhost never enters the allowlist; the legitimate source is unaffected
    assertThat(derivedValidator.isAllowedImageUrl("https://localhost/photo.jpg")).isFalse();
    assertThat(derivedValidator.isAllowedImageUrl("https://img01.kufar.by/photo.jpg")).isTrue();
  }

  @Test
  void should_not_derive_loopback_ip_into_allowlist_from_connector_base_url() {
    // Given
    var environment = new MockEnvironment();
    environment.setProperty("connector.test.base-url", "http://127.0.0.1:9000");

    // When
    var derivedValidator = new ImageUrlValidator(environment);

    // Then
    assertThat(derivedValidator.isAllowedImageUrl("https://127.0.0.1/photo.jpg")).isFalse();
  }

  @Test
  void should_not_derive_link_local_cloud_metadata_address_into_allowlist() {
    // Given — SSRF vector: cloud metadata endpoint configured (accidentally or maliciously) as a
    // connector base-url
    var environment = new MockEnvironment();
    environment.setProperty("connector.test.base-url", "http://169.254.169.254/");

    // When
    var derivedValidator = new ImageUrlValidator(environment);

    // Then
    assertThat(derivedValidator.isAllowedImageUrl("https://169.254.169.254/latest/meta-data/")).isFalse();
  }

  @Test
  void should_not_derive_private_network_address_into_allowlist() {
    // Given — RFC1918 private range
    var environment = new MockEnvironment();
    environment.setProperty("connector.test.base-url", "http://192.168.1.10:8080");

    // When
    var derivedValidator = new ImageUrlValidator(environment);

    // Then
    assertThat(derivedValidator.isAllowedImageUrl("https://192.168.1.10/photo.jpg")).isFalse();
  }

  @Test
  void should_reject_localhost_url_even_when_explicitly_present_in_allowlist() {
    // Given — defense-in-depth: even if "localhost" somehow ended up in the allowlist (e.g. via
    // the explicit Set<String> constructor), isAllowedImageUrl must still refuse it at match time
    var validatorWithLocalhost = new ImageUrlValidator(Set.of("localhost"));

    // When
    boolean result = validatorWithLocalhost.isAllowedImageUrl("https://localhost/photo.jpg");

    // Then
    assertThat(result).isFalse();
  }

  @Test
  void should_reject_private_ip_url_even_when_explicitly_present_in_allowlist() {
    // Given — defense-in-depth, same as above but for a private IP literal
    var validatorWithPrivateIp = new ImageUrlValidator(Set.of("10.0.0.5"));

    // When
    boolean result = validatorWithPrivateIp.isAllowedImageUrl("https://10.0.0.5/photo.jpg");

    // Then
    assertThat(result).isFalse();
  }

  @Test
  void should_reject_ipv6_loopback_literal() {
    // Given
    var validatorWithIpv6Loopback = new ImageUrlValidator(Set.of("::1"));

    // When
    boolean result = validatorWithIpv6Loopback.isAllowedImageUrl("https://[::1]/photo.jpg");

    // Then
    assertThat(result).isFalse();
  }
}
