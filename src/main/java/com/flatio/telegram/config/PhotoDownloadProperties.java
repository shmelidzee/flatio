package com.flatio.telegram.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration identifying the Kufar photo CDN host so {@link
 * com.flatio.telegram.handler.PhotoProxyClient#isKufarCdnUrl} can recognize it.
 *
 * <p>Kufar's photo CDN ({@code rms.kufar.by}) is known to return an HTML page instead of image
 * bytes for requests from this server (issue #497) — a browser-like {@code User-Agent}/{@code
 * Referer} did not resolve it in production (issue #511), so callers bypass server-side download
 * for this host entirely and pass the photo URL straight to Telegram instead (issue #515). The
 * host stays externalized here (never hard-coded) so the bypass can be tuned or disabled without
 * a code change.
 *
 * @param kufarCdnHost host of the Kufar photo CDN that gets the direct-URL bypass; blank disables
 *                     it entirely
 */
@ConfigurationProperties(prefix = "photo-download")
public record PhotoDownloadProperties(
    String kufarCdnHost
) {}
