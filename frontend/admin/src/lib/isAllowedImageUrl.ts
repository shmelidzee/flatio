/**
 * Source CDN host suffixes a listing photo URL is allowed to point to.
 *
 * <p>Mirrors the backend's {@code com.flatio.common.util.ImageUrlValidator} (issue #364) —
 * same allowlist, same reasoning applied on the browser side instead of the server side.
 */
const ALLOWED_HOST_SUFFIXES = ["onliner.by", "kufar.by", "realt.by"];

/**
 * Checks whether a listing's `photoUrl` is safe to render in an `<img src>`.
 *
 * <p>`photoUrl` values originate from data scraped off external listing sources
 * (Onliner/Kufar/Realt), not from anything Flatio constructs itself — a malicious or
 * compromised listing could set it to an attacker-controlled URL that the admin's browser would
 * then request when the moderation modal opens: a tracking pixel revealing which admin opened
 * which listing (IP/User-Agent/timestamp), or an oversized `data:` URI forcing a heavy decode
 * (issue #394). Restricting to the known source CDN hosts over HTTPS closes this off the same
 * way the backend's `PhotoProxyClient` allowlist does for server-side fetches.
 *
 * @param url candidate photo URL, may be null/undefined/blank
 * @returns true if the URL is `https` with a host that is (or is a subdomain of) one of the
 *   known source CDN domains — narrows `url` to `string` for callers using it as a type guard
 */
export function isAllowedImageUrl(url: string | null | undefined): url is string {
  if (!url) {
    return false;
  }
  let parsed: URL;
  try {
    parsed = new URL(url);
  } catch {
    return false;
  }
  if (parsed.protocol !== "https:") {
    return false;
  }
  const host = parsed.hostname.toLowerCase();
  return ALLOWED_HOST_SUFFIXES.some((suffix) => host === suffix || host.endsWith(`.${suffix}`));
}
