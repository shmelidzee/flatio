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
}
