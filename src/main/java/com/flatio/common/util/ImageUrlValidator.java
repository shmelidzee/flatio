package com.flatio.common.util;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Set;

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
 * handful of domains listed here, so private/loopback/link-local addresses and any other host
 * are rejected by construction — there is no separate IP-range denylist to keep in sync.
 *
 * <p>Applied at every point a photo URL crosses a trust boundary: where connectors accept an
 * already-absolute URL from a source's API response instead of building one from a configured
 * CDN base, and at {@code PhotoProxyClient} itself as the last line of defense before the actual
 * outbound request, regardless of what any connector already validated upstream.
 */
public final class ImageUrlValidator {

  private static final Set<String> ALLOWED_HOST_SUFFIXES = Set.of(
      "onliner.by", "kufar.by", "realt.by"
  );

  private ImageUrlValidator() {
  }

  /**
   * Checks whether the given URL is safe to fetch as a listing photo.
   *
   * @param url candidate photo URL, may be null or blank
   * @return true if the URL is {@code https}, has a host, and that host is (or is a subdomain
   *     of) one of the known source CDN domains
   */
  public static boolean isAllowedImageUrl(String url) {
    if (url == null || url.isBlank()) {
      return false;
    }
    try {
      URI uri = new URI(url);
      String host = uri.getHost();
      if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || host.isBlank()) {
        return false;
      }
      String lowerHost = host.toLowerCase();
      return ALLOWED_HOST_SUFFIXES.stream()
          .anyMatch(suffix -> lowerHost.equals(suffix) || lowerHost.endsWith("." + suffix));
    } catch (URISyntaxException e) {
      return false;
    }
  }
}
