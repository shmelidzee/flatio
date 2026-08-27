package com.flatio.common.util;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.stereotype.Component;

/**
 * Validates that a listing photo URL points to a known, trusted source CDN before it is used
 * for a server-side HTTP request or rendered client-side.
 *
 * <p><b>SSRF hardening (issue #364):</b> {@code photoUrl} values ultimately originate from data
 * scraped off external listing sources (Onliner/Kufar/Realt), not from anything Flatio
 * constructs itself — a malicious or compromised listing could set it to an internal address
 * (cloud metadata endpoint, localhost, an internal service) to make the server that later
 * downloads it ({@code PhotoProxyClient}) issue a request on the attacker's behalf. Validating
 * against an explicit host allowlist closes this off structurally: an allowlist only matches the
 * handful of domains derived below, so private/loopback/link-local addresses and any other host
 * are rejected by construction — there is no separate IP-range denylist to keep in sync.
 *
 * <p><b>Allowlist source (issue #424):</b> the allowed registrable domains are derived at startup
 * from every {@code connector.*.base-url} and {@code connector.*.photo-cdn-base-url} property
 * already declared for fetching, instead of being hardcoded here. Adding a new source connector
 * to {@code application.yml} is therefore sufficient to also allowlist its photo CDN — this class
 * never needs to change. The heuristic (last two dot-separated labels of the configured host)
 * matches every source currently configured, all under the single-label {@code .by} TLD; it would
 * need revisiting before onboarding a source under a multi-label TLD (e.g. {@code .co.uk}).
 *
 * <p><b>Loopback/private-address guard (issue #450):</b> a local override
 * ({@code application-local.yml}) pointing a {@code connector.*.base-url} at a dev stub server
 * (e.g. {@code http://localhost:8089}) must never widen the allowlist to loopback, link-local, or
 * private addresses — that would defeat the SSRF hardening above in that profile. Both the
 * allowlist-derivation step and {@link #isAllowedImageUrl} independently refuse such hosts.
 *
 * <p>Applied at every point a photo URL crosses a trust boundary: where connectors accept an
 * already-absolute URL from a source's API response instead of building one from a configured
 * CDN base, and at {@code PhotoProxyClient} itself as the last line of defense before the actual
 * outbound request, regardless of what any connector already validated upstream.
 */
@Component
@Slf4j
public class ImageUrlValidator {

  private static final Pattern CONNECTOR_URL_KEY_PATTERN =
      Pattern.compile("^connector\\.[a-z0-9-]+\\.(base-url|photo-cdn-base-url)$");
  private static final Pattern IPV4_LITERAL_PATTERN = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");

  private final Set<String> allowedHostSuffixes;

  @Autowired
  public ImageUrlValidator(ConfigurableEnvironment environment) {
    this(resolveAllowedHostSuffixes(environment));
    log.info("SSRF photo allowlist derived from connector config: {}", allowedHostSuffixes);
  }

  /**
   * Constructs a validator against an explicit host-suffix allowlist, bypassing environment
   * scanning. Used by the {@link ConfigurableEnvironment} constructor above, and directly by
   * tests that need a deterministic allowlist without a full Spring {@code Environment}.
   *
   * @param allowedHostSuffixes registrable-domain suffixes to allow (e.g. {@code "onliner.by"}), never null
   */
  public ImageUrlValidator(Set<String> allowedHostSuffixes) {
    this.allowedHostSuffixes = Set.copyOf(allowedHostSuffixes);
  }

  /**
   * Checks whether the given URL is safe to fetch as a listing photo.
   *
   * @param url candidate photo URL, may be null or blank
   * @return true if the URL is {@code https}, has a host, that host is not loopback/private/
   *     link-local (issue #450), and the host is (or is a subdomain of) one of the allowed
   *     source CDN domains
   */
  public boolean isAllowedImageUrl(String url) {
    if (url == null || url.isBlank()) {
      return false;
    }
    try {
      URI uri = new URI(url);
      String host = uri.getHost();
      if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || host.isBlank()) {
        return false;
      }
      if (isDisallowedHost(host)) {
        return false;
      }
      String lowerHost = host.toLowerCase(Locale.ROOT);
      return allowedHostSuffixes.stream()
          .anyMatch(suffix -> lowerHost.equals(suffix) || lowerHost.endsWith("." + suffix));
    } catch (URISyntaxException e) {
      return false;
    }
  }

  private static Set<String> resolveAllowedHostSuffixes(ConfigurableEnvironment environment) {
    Set<String> suffixes = new LinkedHashSet<>();
    for (PropertySource<?> source : environment.getPropertySources()) {
      if (source instanceof EnumerablePropertySource<?> enumerable) {
        collectFromSource(environment, enumerable, suffixes);
      }
    }
    return suffixes;
  }

  private static void collectFromSource(ConfigurableEnvironment environment,
      EnumerablePropertySource<?> source, Set<String> suffixes) {
    for (String name : source.getPropertyNames()) {
      if (CONNECTOR_URL_KEY_PATTERN.matcher(name).matches()) {
        addHostSuffix(suffixes, environment.getProperty(name));
      }
    }
  }

  /**
   * Resolves the registrable domain of a configured connector URL and adds it to the allowlist —
   * unless the host itself is loopback, link-local, or private (issue #450), in which case it is
   * refused outright rather than reduced to a (nonsensical, and dangerously broad) domain suffix.
   *
   * @param suffixes the allowlist being built, mutated in place
   * @param url       a configured {@code connector.*.base-url} / {@code photo-cdn-base-url} value
   */
  private static void addHostSuffix(Set<String> suffixes, String url) {
    if (url == null || url.isBlank()) {
      return;
    }
    try {
      String host = new URI(url).getHost();
      if (host == null || host.isBlank()) {
        return;
      }
      if (isDisallowedHost(host)) {
        log.warn("Refusing to derive SSRF allowlist entry from loopback/private/link-local host: {}", host);
        return;
      }
      suffixes.add(registrableDomain(host));
    } catch (URISyntaxException e) {
      log.warn("Ignoring unparsable connector URL while building SSRF allowlist: {}", url);
    }
  }

  private static String registrableDomain(String host) {
    String lower = host.toLowerCase(Locale.ROOT);
    String[] labels = lower.split("\\.");
    return labels.length <= 2 ? lower : labels[labels.length - 2] + "." + labels[labels.length - 1];
  }

  /**
   * Checks whether a host is {@code localhost}, or a loopback/link-local/private/multicast IP
   * literal (issue #450) — the classes of address an SSRF allowlist must never cover, cloud
   * metadata endpoints included (link-local range).
   *
   * <p>Only recognized IP-literal strings are resolved via {@link InetAddress#getByName}; that
   * call performs no DNS lookup for a literal address, so this never resolves an arbitrary
   * hostname.
   *
   * @param rawHost the host to check, never null
   * @return true if the host must never appear in the allowlist or match a candidate URL
   */
  private static boolean isDisallowedHost(String rawHost) {
    String host = rawHost.toLowerCase(Locale.ROOT);
    if (host.startsWith("[") && host.endsWith("]")) {
      host = host.substring(1, host.length() - 1);
    }
    if (host.equals("localhost") || host.endsWith(".localhost")) {
      return true;
    }
    if (!IPV4_LITERAL_PATTERN.matcher(host).matches() && !host.contains(":")) {
      return false;
    }
    try {
      InetAddress address = InetAddress.getByName(host);
      return address.isLoopbackAddress()
          || address.isLinkLocalAddress()
          || address.isSiteLocalAddress()
          || address.isAnyLocalAddress()
          || address.isMulticastAddress();
    } catch (UnknownHostException e) {
      return false;
    }
  }
}
