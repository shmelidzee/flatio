package com.flatio.telegram.state;

import com.flatio.web.dto.ListingSearchCriteria;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * In-memory search session that tracks pagination state for a single user.
 *
 * <p>Stored in {@link SearchResultSender} keyed by Telegram user ID.
 * Expires after 30 minutes of inactivity — checked on every access.
 */
@Getter
@Setter
public class SearchSession {

  private final ListingSearchCriteria criteria;
  private long currentPage;
  private long totalPages;
  private Instant lastAccessedAt;

  public SearchSession(ListingSearchCriteria criteria, long currentPage, long totalPages) {
    this.criteria = criteria;
    this.currentPage = currentPage;
    this.totalPages = totalPages;
    this.lastAccessedAt = Instant.now();
  }

  /** Updates the last-accessed timestamp to extend the session lifetime. */
  public void touch() {
    this.lastAccessedAt = Instant.now();
  }
}
