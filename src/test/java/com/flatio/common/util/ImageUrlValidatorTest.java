package com.flatio.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ImageUrlValidatorTest {

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
    boolean result = ImageUrlValidator.isAllowedImageUrl(url);

    // Then
    assertThat(result).isTrue();
  }

  @Test
  void should_return_false_when_url_is_null() {
    // When
    boolean result = ImageUrlValidator.isAllowedImageUrl(null);

    // Then
    assertThat(result).isFalse();
  }

  @Test
  void should_return_false_when_url_is_blank() {
    // When
    boolean result = ImageUrlValidator.isAllowedImageUrl("   ");

    // Then
    assertThat(result).isFalse();
  }

  @Test
  void should_return_false_when_scheme_is_http() {
    // When
    boolean result = ImageUrlValidator.isAllowedImageUrl("http://onliner.by/photo.jpg");

    // Then
    assertThat(result).isFalse();
  }

  @Test
  void should_return_false_when_host_is_not_on_allowlist() {
    // When — SSRF vector: arbitrary attacker-controlled host
    boolean result = ImageUrlValidator.isAllowedImageUrl("https://evil.com/photo.jpg");

    // Then
    assertThat(result).isFalse();
  }

  @Test
  void should_return_false_when_host_only_contains_allowlisted_suffix_as_substring() {
    // When — SSRF vector: "onliner.by.evil.com" contains "onliner.by" as a substring, not a suffix
    boolean result = ImageUrlValidator.isAllowedImageUrl("https://onliner.by.evil.com/photo.jpg");

    // Then
    assertThat(result).isFalse();
  }

  @Test
  void should_return_false_when_host_is_loopback_address() {
    // When — SSRF vector: loopback address
    boolean result = ImageUrlValidator.isAllowedImageUrl("https://127.0.0.1/photo.jpg");

    // Then
    assertThat(result).isFalse();
  }

  @Test
  void should_return_false_when_host_is_cloud_metadata_address() {
    // When — SSRF vector: cloud metadata endpoint
    boolean result = ImageUrlValidator.isAllowedImageUrl("https://169.254.169.254/latest/meta-data/");

    // Then
    assertThat(result).isFalse();
  }

  @Test
  void should_return_false_when_url_has_no_host() {
    // When
    boolean result = ImageUrlValidator.isAllowedImageUrl("https:///photo.jpg");

    // Then
    assertThat(result).isFalse();
  }

  @Test
  void should_return_false_when_url_is_malformed() {
    // When
    boolean result = ImageUrlValidator.isAllowedImageUrl("not a url at all ::");

    // Then
    assertThat(result).isFalse();
  }

  @Test
  void should_be_case_insensitive_for_scheme_and_host() {
    // When
    boolean result = ImageUrlValidator.isAllowedImageUrl("HTTPS://CDN.ONLINER.BY/photo.jpg");

    // Then
    assertThat(result).isTrue();
  }
}
