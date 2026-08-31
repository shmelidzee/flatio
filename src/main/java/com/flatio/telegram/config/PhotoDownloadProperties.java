package com.flatio.telegram.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for source-specific HTTP headers applied when downloading listing photo bytes
 * via {@link com.flatio.telegram.handler.PhotoProxyClient}.
 *
 * <p>Kufar's photo CDN ({@code rms.kufar.by}) is known to occasionally return an HTML page
 * instead of image bytes for requests that look automated (issue #497) — most likely an
 * anti-bot or geo-based gate reacting to a missing/non-browser {@code User-Agent} and
 * {@code Referer}. These values are configured (never hard-coded) so they can be tuned or
 * disabled without a code change, and so the CDN host used to decide whether to apply them is
 * not a literal baked into request-handling code.
 *
 * @param kufarCdnHost   host of the Kufar photo CDN that receives the browser-like headers below;
 *                       blank disables the override entirely
 * @param kufarUserAgent {@code User-Agent} sent for requests to {@code kufarCdnHost}
 * @param kufarReferer   {@code Referer} sent for requests to {@code kufarCdnHost}
 */
@ConfigurationProperties(prefix = "photo-download")
public record PhotoDownloadProperties(
    String kufarCdnHost,
    String kufarUserAgent,
    String kufarReferer
) {}
