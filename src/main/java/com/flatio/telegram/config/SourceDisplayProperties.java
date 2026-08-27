package com.flatio.telegram.config;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Per-source presentation settings for Telegram listing cards (issue #423).
 *
 * <p>Maps a listing's {@code sourceId} prefix (e.g. {@code REALT}, {@code ONLINER},
 * {@code KUFAR} — connector source IDs like {@code REALT_HOUSE_SALE} all share the
 * {@code REALT} prefix) to a display badge and whether the "address not specified" label applies.
 * Adding a new source is a configuration change under {@code telegram.source-display.sources},
 * not a code change.
 */
@Component
@ConfigurationProperties(prefix = "telegram.source-display")
@Getter
@Setter
public class SourceDisplayProperties {

  private List<Entry> sources = List.of();

  /**
   * Finds the configured entry whose {@link Entry#getPrefix()} the given source ID starts with
   * (case-insensitive).
   *
   * @param sourceId the listing's source ID, may be null
   * @return the matching entry, or empty if none configured for this source
   */
  public Optional<Entry> findBySourceId(String sourceId) {
    if (sourceId == null || sourceId.isBlank()) {
      return Optional.empty();
    }
    String upper = sourceId.toUpperCase(Locale.ROOT);
    return sources.stream()
        .filter(entry -> upper.startsWith(entry.getPrefix().toUpperCase(Locale.ROOT)))
        .findFirst();
  }

  @Getter
  @Setter
  public static class Entry {

    private String prefix;
    private String displayName;
    private boolean addressUnknownLabelEnabled;
  }
}
