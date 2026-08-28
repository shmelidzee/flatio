package com.flatio.telegram.state;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.Collections;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Tracks, per Telegram user, a pending "add stop-word to blacklist" flow awaiting the keyword as
 * free text (issue #459).
 *
 * <p>Mirrors the lightweight Caffeine-backed state pattern used by {@link SearchFilterWizard} —
 * this is a single prompt-then-reply exchange, not a multi-step wizard, so a dedicated FSM
 * framework would be overkill. Entries expire {@value #STATE_TTL_MINUTES} minutes after the
 * prompt was issued so an abandoned flow does not linger in memory.
 */
@Component
public class BlacklistKeywordPromptState {

  private static final long STATE_TTL_MINUTES = 10;
  private static final long MAX_STATES = 10_000;

  private final Set<Long> awaiting = Collections.newSetFromMap(
      Caffeine.newBuilder()
          .expireAfterWrite(Duration.ofMinutes(STATE_TTL_MINUTES))
          .maximumSize(MAX_STATES)
          .<Long, Boolean>build()
          .asMap());

  /**
   * Marks the given user as awaiting a stop-word.
   *
   * @param telegramId Telegram user identifier, never null
   */
  public void await(Long telegramId) {
    awaiting.add(telegramId);
  }

  /**
   * Checks whether the given user is currently awaiting a stop-word.
   *
   * @param telegramId Telegram user identifier, never null
   * @return true if a stop-word prompt is pending for this user
   */
  public boolean isAwaiting(Long telegramId) {
    return awaiting.contains(telegramId);
  }

  /**
   * Clears the pending prompt for the given user.
   *
   * @param telegramId Telegram user identifier, never null
   */
  public void clear(Long telegramId) {
    awaiting.remove(telegramId);
  }
}
