package com.flatio.common.util;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.stereotype.Component;

/**
 * Validates that a listing photo URL points to an address that is safe for the server to fetch,
 * or for a client to render directly.
 *
 * <p><b>SSRF hardening (issue #364):</b> {@code photoUrl} values ultimately originate from data
 * scraped off external listing sources (Onliner/Kufar/Realt), not from anything Flatio
 * constructs itself — a malicious or compromised listing could set it to an internal address
 * (cloud metadata endpoint, localhost, an internal service) to make the server that later
 * downloads it ({@code PhotoProxyClient}) issue a request on the attacker's behalf. Rejecting
 * loopback/private/link-local hosts (see {@link #isDisallowedHost}) closes this off structurally.
 *
 * <p><b>Source-domain allowlist removed (issue #455):</b> issues #424 and #453 additionally
 * restricted photo URLs to hosts that reduced to the same registrable domain as a connector's
 * configured {@code base-url}/{@code photo-cdn-base-url} (last two dot-separated labels). In
 * production this rejected legitimate photos served from a source CDN whose host does not reduce
 * to that same registrable domain — a separate CDN domain or a subdomain the heuristic did not
 * anticipate — which regressed most Telegram photo cards to a placeholder. Per explicit
 * product-owner direction on issue #455, that domain-based restriction is removed entirely: any
 * {@code https} URL is now accepted as long as its host is not loopback/private/link-local. The
 * loopback/private/link-local guard (issue #450) is unchanged and remains the sole SSRF defense —
 * connector configuration is no longer scanned to build a host allowlist.
 *
 * <p>The {@link #ImageUrlValidator(Set)} and {@link #ImageUrlValidator(ConfigurableEnvironment)}
 * constructors are retained only so call sites and tests written against the pre-#455 API keep
 * compiling; neither argument affects validation any more — see their Javadoc.
 *
 * <p>Applied at every point a photo URL crosses a trust boundary: where connectors accept an
 * already-absolute URL from a source's API response instead of building one from a configured
 * CDN base, and at {@code PhotoProxyClient} itself as the last line of defense before the actual
 * outbound request, regardless of what any connector already validated upstream.
 */
@Component
@Slf4j
public class ImageUrlValidator {

  private static final Pattern IPV4_LITERAL_PATTERN = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");

  @Autowired
  public ImageUrlValidator() {
    log.info("ImageUrlValidator initialized (issue #455): HTTPS photo URLs are accepted for any "
        + "host that is not loopback/private/link-local; the source-domain allowlist was removed");
  }

  /**
   * Legacy constructor kept for source compatibility with call sites built against the
   * pre-issue-#455 API, where validation was restricted to an explicit set of registrable
   * domains. The set no longer affects validation — see the class Javadoc.
   *
   * @param legacyAllowedHostSuffixes ignored, retained only for backward source compatibility
   */
  public ImageUrlValidator(Set<String> legacyAllowedHostSuffixes) {
    this();
  }

  /**
   * Legacy constructor kept for source compatibility with call sites built against the
   * pre-issue-#455 API, where the allowlist was derived from {@code connector.*.base-url} /
   * {@code connector.*.photo-cdn-base-url} properties. The environment is no longer scanned —
   * see the class Javadoc.
   *
   * @param environment ignored, retained only for backward source compatibility
   */
  public ImageUrlValidator(ConfigurableEnvironment environment) {
    this();
  }

  /**
   * Checks whether the given URL is safe to fetch as a listing photo.
   *
   * @param url candidate photo URL, may be null or blank
   * @return true if the URL is {@code https}, has a host, and that host is not loopback/private/
   *     link-local (issue #450); false otherwise, with the rejection reason logged at WARN
   */
  public boolean isAllowedImageUrl(String url) {
    if (url == null || url.isBlank()) {
      return false;
    }
    try {
      URI uri = new URI(url);
      String host = uri.getHost();
      if (!"https".equalsIgnoreCase(uri.getScheme())) {
        log.warn("Rejecting photo URL with disallowed scheme: url={}, scheme={}", url, uri.getScheme());
        return false;
      }
      if (host == null || host.isBlank()) {
        log.warn("Rejecting photo URL with no host: url={}", url);
        return false;
      }
      if (isDisallowedHost(host)) {
        log.warn("Rejecting photo URL pointing to a loopback/private/link-local host: url={}, host={}", url, host);
        return false;
      }
      return true;
    } catch (URISyntaxException e) {
      log.warn("Rejecting malformed photo URL: url={}, error={}", url, e.getMessage());
      return false;
    }
  }

  /**
   * Checks whether a host is {@code localhost}, or a loopback/link-local/private/multicast IP
   * literal (issue #450) — the classes of address an SSRF guard must never allow, cloud metadata
   * endpoints included (link-local range). This is the only remaining validation gate after the
   * source-domain allowlist was removed (issue #455).
   *
   * <p>Only recognized IP-literal strings are resolved via {@link InetAddress#getByName}; that
   * call performs no DNS lookup for a literal address, so this never resolves an arbitrary
   * hostname.
   *
   * @param rawHost the host to check, never null
   * @return true if the host must never be treated as a valid photo URL host
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
