package com.flatio.common.util;

import java.net.InetAddress;
import java.net.UnknownHostException;
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
 * <p><b>Deterministic, network-free DNS coverage (issue #455 QA follow-up, package-private
 * {@link ImageUrlValidator#ImageUrlValidator(ImageUrlValidator.HostResolver)} constructor):</b>
 * {@code isDisallowedHost} resolves every host via an injected {@link ImageUrlValidator.HostResolver}
 * seam rather than calling {@link InetAddress#getAllByName} directly. Tests that need an "ordinary
 * external hostname" case use a fabricated domain (e.g. {@code cdn.example-source.test}) paired with
 * a fake resolver, instead of a real public host — this is what lets the DNS-based SSRF bypass (a
 * domain the attacker controls DNS for, resolving to a loopback/private/metadata address) and the
 * fail-closed {@link UnknownHostException} path be asserted deterministically, without any live
 * network access. Tests that only need an IP literal directly in the URL (127.0.0.1, 10.0.0.5, the
 * alternate IPv4 notations, {@code localhost}, ...) stay on the plain no-arg constructor: an IP
 * literal is parsed locally by {@link InetAddress#getAllByName} with no DNS round-trip, so that path
 * was already network-free before this seam existed.
 */
class ImageUrlValidatorTest {

  private static final String PUBLIC_IP = "93.184.216.34";

  private ImageUrlValidator validator;
  private ImageUrlValidator publicHostValidator;

  @BeforeEach
  void setUp() {
    // Real resolver — safe here because every case exercised against it below uses either an IP
    // literal (parsed locally, no DNS) or the localhost fast path (no resolver call at all).
    validator = new ImageUrlValidator();
    // Fake resolver — returns an ordinary public address for any host at all, so an arbitrary
    // fabricated external domain can stand in for "a legitimate source CDN host" without a live
    // DNS lookup.
    publicHostValidator = new ImageUrlValidator(resolverReturning(PUBLIC_IP));
  }

  // -------------------------------------------------------------------------
  // isAllowedImageUrl — any https URL with a public host is accepted
  // -------------------------------------------------------------------------

  @ParameterizedTest
  @ValueSource(strings = {
      "https://cdn.example-source.test/photo.jpg",
      "https://photos.another-cdn.test/photo.jpg",
      "https://static.random-listing-host.test/photo.jpg",
      // Not a registrable subdomain of any source in application.yml — this is exactly the class
      // of legitimate, unrelated CDN host that the removed allowlist heuristic used to reject.
      "https://assets.unrelated-cdn.test/photo.jpg"
  })
  void should_return_true_when_url_is_https_with_public_host(String url) {
    // When
    boolean result = publicHostValidator.isAllowedImageUrl(url);

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
    boolean result = publicHostValidator.isAllowedImageUrl("HTTPS://CDN.EXAMPLE-SOURCE.TEST/photo.jpg");

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
  // DNS-based SSRF bypass (CRITICAL security review finding, issue #455, fb6790f) — a domain the
  // attacker controls DNS for, whose record resolves to a loopback/private/link-local address, is
  // exactly as much an SSRF vector as an IP literal typed directly into the URL. This is the one
  // scenario that genuinely requires a resolver seam to test deterministically: the malicious
  // behavior only exists in what DNS *returns* for an otherwise ordinary-looking external hostname.
  // -------------------------------------------------------------------------

  @ParameterizedTest
  @ValueSource(strings = {
      "127.0.0.1",       // loopback
      "10.0.0.5",        // private (RFC1918)
      "192.168.1.10",    // private (RFC1918)
      "169.254.169.254", // link-local — cloud metadata endpoint
      "0.0.0.0",         // any-local
      "224.0.0.1"        // multicast
  })
  void should_return_false_when_external_host_resolves_to_disallowed_address(String maliciousIp) {
    // Given — a fabricated external CDN domain whose DNS record is under the attacker's control,
    // pointing at an internal/loopback/metadata address instead of the CDN it appears to be
    var dnsBypassValidator = new ImageUrlValidator(resolverReturning(maliciousIp));

    // When
    boolean result = dnsBypassValidator.isAllowedImageUrl("https://attacker-controlled-cdn.test/photo.jpg");

    // Then
    assertThat(result).isFalse();
  }

  @Test
  void should_return_false_when_any_of_multiple_resolved_addresses_is_disallowed() {
    // Given — a host resolving to more than one address (common for real CDNs); only one of them
    // needs to be disallowed for the host as a whole to be rejected
    var dnsBypassValidator = new ImageUrlValidator(resolverReturning(PUBLIC_IP, "127.0.0.1"));

    // When
    boolean result = dnsBypassValidator.isAllowedImageUrl("https://multi-address-cdn.test/photo.jpg");

    // Then
    assertThat(result).isFalse();
  }

  // -------------------------------------------------------------------------
  // Fail-closed on unresolvable host (issue #455) — a photo URL whose host cannot be resolved at
  // all cannot be a legitimate source CDN photo either, so it must be rejected rather than allowed.
  // -------------------------------------------------------------------------

  @Test
  void should_return_false_when_dns_resolution_fails() {
    // Given — resolver simulates a host that fails DNS resolution entirely, without any real
    // network timeout
    var unresolvableValidator = new ImageUrlValidator(resolverThrowing());

    // When
    boolean result = unresolvableValidator.isAllowedImageUrl("https://nonexistent-cdn.test/photo.jpg");

    // Then
    assertThat(result).isFalse();
  }

  // -------------------------------------------------------------------------
  // Legacy constructors (Set<String>, ConfigurableEnvironment) — retained only for source
  // compatibility with call sites/tests written against the pre-#455 API (issue #455). Both
  // delegate to the real production resolver, so assertions here use public/private IP literals
  // rather than hostnames to stay network-free.
  // -------------------------------------------------------------------------

  // Both legacy constructors are @Deprecated(forRemoval = true) since issue #461 — suppressed here
  // because these four tests exist specifically to keep asserting their no-op contract.
  @SuppressWarnings({"deprecation", "removal"})
  @Test
  void should_behave_like_default_constructor_when_using_legacy_set_constructor() {
    // Given — the legacy allowlist argument is ignored entirely, even a narrow one
    var legacyValidator = new ImageUrlValidator(Set.of("onliner.by"));

    // When / Then — a host outside the legacy set is still accepted (allowlist removed); a public
    // IP literal is used instead of a hostname so this assertion does not depend on live DNS
    assertThat(legacyValidator.isAllowedImageUrl("https://" + PUBLIC_IP + "/photo.jpg")).isTrue();
    // And the SSRF guard still applies regardless of the legacy argument
    assertThat(legacyValidator.isAllowedImageUrl("https://127.0.0.1/photo.jpg")).isFalse();
  }

  @SuppressWarnings({"deprecation", "removal"})
  @Test
  void should_not_throw_when_using_legacy_set_constructor_with_empty_set() {
    // When / Then — an empty legacy allowlist must not narrow validation or throw
    assertThatNoException().isThrownBy(() -> new ImageUrlValidator(Set.of()));
    assertThat(new ImageUrlValidator(Set.of()).isAllowedImageUrl("https://" + PUBLIC_IP + "/photo.jpg")).isTrue();
  }

  @SuppressWarnings({"deprecation", "removal"})
  @Test
  void should_behave_like_default_constructor_when_using_legacy_environment_constructor() {
    // Given — mirrors the shape of application.yml's connector.* block; none of it is scanned any more
    var environment = new MockEnvironment();
    environment.setProperty("connector.onliner.base-url", "https://ak.api.onliner.by");
    environment.setProperty("connector.kufar.base-url", "https://api.kufar.by");

    // When
    var legacyValidator = new ImageUrlValidator(environment);

    // Then — hosts unrelated to any configured connector are still accepted; a public IP literal
    // avoids a live DNS dependency
    assertThat(legacyValidator.isAllowedImageUrl("https://" + PUBLIC_IP + "/photo.jpg")).isTrue();
    // And the SSRF guard still applies regardless of connector configuration
    assertThat(legacyValidator.isAllowedImageUrl("https://192.168.1.10/photo.jpg")).isFalse();
  }

  @SuppressWarnings({"deprecation", "removal"})
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

  // -------------------------------------------------------------------------
  // Helpers — fake HostResolver
  // -------------------------------------------------------------------------

  /**
   * Builds a fake {@link ImageUrlValidator.HostResolver} that resolves any host to the given IP
   * literals, regardless of what host is asked for. Each literal is parsed by
   * {@link InetAddress#getByName(String)} locally — since it is already a numeric address, no DNS
   * lookup is performed — so this stays network-free.
   */
  private static ImageUrlValidator.HostResolver resolverReturning(String... ipLiterals) {
    return host -> {
      var addresses = new InetAddress[ipLiterals.length];
      for (int i = 0; i < ipLiterals.length; i++) {
        addresses[i] = InetAddress.getByName(ipLiterals[i]);
      }
      return addresses;
    };
  }

  /**
   * Builds a fake {@link ImageUrlValidator.HostResolver} that simulates a host failing DNS
   * resolution entirely, without any real network call or timeout.
   */
  private static ImageUrlValidator.HostResolver resolverThrowing() {
    return host -> {
      throw new UnknownHostException("Simulated DNS failure for host: " + host);
    };
  }
}
