package com.flatio.common.util;

import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * Covers {@link ImageUrlValidator} after the source-domain allowlist was removed (issue #455).
 *
 * <p>Before #455, {@code isAllowedImageUrl} additionally required the host to reduce to the same
 * registrable domain as a configured {@code connector.*.base-url}/{@code photo-cdn-base-url}. That
 * restriction rejected legitimate source CDN photos and regressed most Telegram photo cards to a
 * placeholder, so it was removed entirely per product-owner direction. The only remaining gate is
 * the loopback/private/link-local address guard from issue #450 — every test here asserts against
 * that guard, not against any notion of an allowed domain.
 *
 * <p><b>Positive (should-be-allowed) hostname cases now require live DNS resolution (issue #455,
 * fb6790f):</b> {@code isDisallowedHost} resolves every host, not just IP literals, so an
 * "allowed" assertion for an ordinary hostname is only true if that hostname actually resolves at
 * test-run time — a fictional/example fixture domain (e.g. {@code random-photo-cdn.example.com},
 * {@code d1a2b3c4.cloudfront.net}) now fails to resolve and is wrongly rejected regardless of test
 * environment, because those domains do not exist in DNS anywhere, not because of any local
 * network restriction. The hostname fixtures below were chosen to be real, stable, long-lived
 * public domains unrelated to any Flatio connector so this suite stays deterministic; see the
 * class-level recommendation in the issue #455 QA report to make {@code isDisallowedHost}'s
 * resolver injectable so this class of test does not need live network access at all.
 */
class ImageUrlValidatorTest {

  private ImageUrlValidator validator;

  @BeforeEach
  void setUp() {
    validator = new ImageUrlValidator();
  }

  // -------------------------------------------------------------------------
  // isAllowedImageUrl — any https URL with a public host is accepted
  // -------------------------------------------------------------------------

  @ParameterizedTest
  @ValueSource(strings = {
      "https://onliner.by/photo.jpg",
      "https://content.onliner.by/photo.jpg",
      "https://kufar.by/photo.jpg",
      // Kufar's actual configured photo-cdn-base-url host (application.yml: connector.kufar.
      // photo-cdn-base-url) — real and resolvable, unlike the fictional "img01.kufar.by" this
      // fixture previously used, which does not exist in DNS and made this case flaky/slow.
      "https://rms.kufar.by/photo.jpg",
      "https://realt.by/photo.jpg",
      "https://cdn.realt.by/photo.jpg",
      // Not a registrable subdomain of any source in application.yml — this is exactly the class
      // of legitimate, unrelated CDN host that the removed allowlist heuristic used to reject.
      // Real, stable public hosts (not fictional example.com/cloudfront.net fixtures) so
      // resolution — required by every host since fb6790f — succeeds deterministically.
      "https://raw.githubusercontent.com/photo.jpg",
      "https://i.imgur.com/photo.jpg"
  })
  void should_return_true_when_url_is_https_with_public_host(String url) {
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
  void should_return_false_when_url_has_no_host() {
    // When
    boolean result = validator.isAllowedImageUrl("https:///photo.jpg");

    // Then
    assertThat(result).isFalse();
  }

  @Test
  void should_return_false_when_url_is_malformed() {
    // When / Then — malformed input must not throw, just fail validation
    assertThatNoException().isThrownBy(() -> {
      boolean result = validator.isAllowedImageUrl("not a url at all ::");
      assertThat(result).isFalse();
    });
  }

  @Test
  void should_be_case_insensitive_for_scheme() {
    // When
    boolean result = validator.isAllowedImageUrl("HTTPS://CDN.ONLINER.BY/photo.jpg");

    // Then
    assertThat(result).isTrue();
  }

  // -------------------------------------------------------------------------
  // Loopback/private/link-local address guard (issue #450) — the sole remaining SSRF defense
  // -------------------------------------------------------------------------

  @ParameterizedTest
  @ValueSource(strings = {
      "https://localhost/photo.jpg",
      "https://sub.localhost/photo.jpg",
      "https://127.0.0.1/photo.jpg",
      "https://127.0.0.5/photo.jpg"
  })
  void should_return_false_when_host_is_loopback(String url) {
    // When
    boolean result = validator.isAllowedImageUrl(url);

    // Then
    assertThat(result).isFalse();
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "https://10.0.0.5/photo.jpg",
      "https://192.168.1.10/photo.jpg",
      "https://172.16.0.1/photo.jpg",
      "https://172.31.255.254/photo.jpg"
  })
  void should_return_false_when_host_is_private_network_address(String url) {
    // When
    boolean result = validator.isAllowedImageUrl(url);

    // Then
    assertThat(result).isFalse();
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "https://169.254.169.254/latest/meta-data/",
      "https://169.254.0.1/photo.jpg"
  })
  void should_return_false_when_host_is_link_local_address(String url) {
    // When — SSRF vector: link-local range includes the cloud metadata endpoint
    boolean result = validator.isAllowedImageUrl(url);

    // Then
    assertThat(result).isFalse();
  }

  @Test
  void should_return_true_when_host_is_outside_private_range_but_numerically_close() {
    // Given — 172.32.0.1 is outside the RFC1918 172.16.0.0/12 block (which ends at 172.31.255.255)
    // and must not be mistaken for a private address by a naive prefix check.

    // When
    boolean result = validator.isAllowedImageUrl("https://172.32.0.1/photo.jpg");

    // Then
    assertThat(result).isTrue();
  }

  @Test
  void should_return_false_when_host_is_ipv6_loopback_literal() {
    // When
    boolean result = validator.isAllowedImageUrl("https://[::1]/photo.jpg");

    // Then
    assertThat(result).isFalse();
  }

  @Test
  void should_return_false_when_host_is_any_local_address() {
    // When — 0.0.0.0 is InetAddress#isAnyLocalAddress
    boolean result = validator.isAllowedImageUrl("https://0.0.0.0/photo.jpg");

    // Then
    assertThat(result).isFalse();
  }

  @Test
  void should_return_false_when_host_is_multicast_address() {
    // When
    boolean result = validator.isAllowedImageUrl("https://224.0.0.1/photo.jpg");

    // Then
    assertThat(result).isFalse();
  }

  // -------------------------------------------------------------------------
  // Alternate IPv4 literal notations (security review MEDIUM finding, issue #455) — the removed
  // IPV4_LITERAL_PATTERN regex pre-filter only recognized dotted-decimal (e.g. "127.0.0.1") before
  // deciding whether to resolve a host at all. InetAddress#getAllByName parses several other
  // classic inet_aton-style IPv4 notations as pure numeric literals — no DNS lookup involved, so
  // this is deterministic and safe to assert without network access. Verified on this JDK
  // (21.0.9): decimal and "short form" notations below both parse locally in 0-3ms.
  //
  // Hex notation ("0x7f000001") is intentionally NOT covered here: on this JDK it is not
  // recognized as an IPv4 literal by InetAddress at all (throws UnknownHostException). It would
  // still end up rejected by isAllowedImageUrl, but only via the fail-closed
  // unresolvable-host branch, not because it resolves to a loopback address — and unlike the pure
  // numeric literals below, whether "0x7f000001" is treated as a literal or as a hostname that
  // needs an actual DNS query is not guaranteed to be identical across JDK/OS resolver
  // implementations, so asserting on it here would not be a deterministic unit test.
  @ParameterizedTest
  @ValueSource(strings = {
      "https://2130706433/photo.jpg", // decimal notation for 127.0.0.1
      "https://127.1/photo.jpg"       // short form for 127.0.0.1
  })
  void should_return_false_when_host_is_alternate_ipv4_notation_for_loopback(String url) {
    // When
    boolean result = validator.isAllowedImageUrl(url);

    // Then
    assertThat(result).isFalse();
  }

  // -------------------------------------------------------------------------
  // Legacy constructors (Set<String>, ConfigurableEnvironment) — retained only for source
  // compatibility with call sites/tests written against the pre-#455 API (issue #455).
  // -------------------------------------------------------------------------

  @Test
  void should_behave_like_default_constructor_when_using_legacy_set_constructor() {
    // Given — the legacy allowlist argument is ignored entirely, even a narrow one
    var legacyValidator = new ImageUrlValidator(Set.of("onliner.by"));

    // When / Then — a host outside the legacy set is still accepted (allowlist removed)
    assertThat(legacyValidator.isAllowedImageUrl("https://raw.githubusercontent.com/photo.jpg")).isTrue();
    // And the SSRF guard still applies regardless of the legacy argument
    assertThat(legacyValidator.isAllowedImageUrl("https://127.0.0.1/photo.jpg")).isFalse();
  }

  @Test
  void should_not_throw_when_using_legacy_set_constructor_with_empty_set() {
    // When / Then — an empty legacy allowlist must not narrow validation or throw
    assertThatNoException().isThrownBy(() -> new ImageUrlValidator(Set.of()));
    assertThat(new ImageUrlValidator(Set.of()).isAllowedImageUrl("https://onliner.by/photo.jpg")).isTrue();
  }

  @Test
  void should_behave_like_default_constructor_when_using_legacy_environment_constructor() {
    // Given — mirrors the shape of application.yml's connector.* block; none of it is scanned any more
    var environment = new MockEnvironment();
    environment.setProperty("connector.onliner.base-url", "https://ak.api.onliner.by");
    environment.setProperty("connector.kufar.base-url", "https://api.kufar.by");

    // When
    var legacyValidator = new ImageUrlValidator(environment);

    // Then — hosts unrelated to any configured connector are still accepted
    assertThat(legacyValidator.isAllowedImageUrl("https://raw.githubusercontent.com/photo.jpg")).isTrue();
    // And the SSRF guard still applies regardless of connector configuration
    assertThat(legacyValidator.isAllowedImageUrl("https://192.168.1.10/photo.jpg")).isFalse();
  }

  @Test
  void should_not_widen_ssrf_guard_when_legacy_environment_points_connector_at_localhost() {
    // Given — the exact scenario from issue #450: a local override (e.g. application-local.yml)
    // points a connector at a dev stub server; this must never widen what the SSRF guard rejects
    var environment = new MockEnvironment();
    environment.setProperty("connector.onliner.base-url", "http://localhost:8089");

    // When
    var legacyValidator = new ImageUrlValidator(environment);

    // Then
    assertThat(legacyValidator.isAllowedImageUrl("https://localhost/photo.jpg")).isFalse();
  }
}
