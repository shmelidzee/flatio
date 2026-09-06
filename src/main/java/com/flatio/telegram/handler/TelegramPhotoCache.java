package com.flatio.telegram.handler;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Caches the Telegram {@code file_id} returned after a listing photo is successfully delivered
 * to a chat, keyed by the source photo URL (issue #521).
 *
 * <p>Without this cache, the same listing photo is re-fetched (or, for Kufar, handed to Telegram
 * as a URL for Telegram itself to fetch) on every card render, with an independent chance of
 * failure each time — the reported symptom was the exact same listing showing a real photo in
 * one view and the placeholder in another. Reusing a cached file_id instead sends the
 * already-delivered bytes back from Telegram's own servers, which does not depend on the source
 * CDN or its anti-bot/geo gate at all.
 *
 * <p>Caffeine, not a plain map, so a listing whose photo is never viewed again does not occupy
 * memory for the lifetime of the JVM — same rationale as the per-user session caches elsewhere
 * in this package (e.g. {@link SearchResultSender}'s search sessions).
 */
@Component
public class TelegramPhotoCache {

  private static final long MAX_ENTRIES = 50_000;
  private static final Duration TTL = Duration.ofDays(30);

  private final Map<String, String> fileIdsByPhotoUrl = Caffeine.newBuilder()
      .expireAfterWrite(TTL)
      .maximumSize(MAX_ENTRIES)
      .<String, String>build()
      .asMap();

  /**
   * Looks up a previously-cached Telegram file_id for the given photo URL.
   *
   * @param photoUrl source photo URL, never null
   * @return the cached file_id, or null if this photo has not been successfully delivered yet
   */
  public String get(String photoUrl) {
    return fileIdsByPhotoUrl.get(photoUrl);
  }

  /**
   * Caches the Telegram file_id returned after successfully delivering the given photo URL.
   *
   * @param photoUrl source photo URL, never null
   * @param fileId   Telegram file_id to reuse on future sends, never null
   */
  public void put(String photoUrl, String fileId) {
    fileIdsByPhotoUrl.put(photoUrl, fileId);
  }

  /**
   * Evicts a cached file_id, used when Telegram rejects a previously-valid file_id (issue #521)
   * so the next render falls back to re-fetching from source instead of retrying the same
   * now-invalid file_id forever.
   *
   * @param photoUrl source photo URL, never null
   */
  public void evict(String photoUrl) {
    fileIdsByPhotoUrl.remove(photoUrl);
  }
}
