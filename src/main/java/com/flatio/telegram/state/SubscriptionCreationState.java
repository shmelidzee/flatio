package com.flatio.telegram.state;

import com.flatio.web.dto.SubscriptionSearchCriteria;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Tracks, per Telegram user, a pending "subscribe to this search" flow awaiting the subscription
 * name as free text (issue #458).
 *
 * <p>Mirrors the lightweight Caffeine-backed state pattern used by {@link SearchFilterWizard} —
 * this is a single prompt-then-reply exchange, not a multi-step wizard, so a dedicated FSM
 * framework would be overkill. Entries expire {@value #STATE_TTL_MINUTES} minutes after the
 * prompt was issued so an abandoned flow does not linger in memory.
 */
@Component
public class SubscriptionCreationState {

  private static final long STATE_TTL_MINUTES = 10;
  private static final long MAX_STATES = 10_000;

  private final Map<Long, SubscriptionSearchCriteria> pending = Caffeine.newBuilder()
      .expireAfterWrite(Duration.ofMinutes(STATE_TTL_MINUTES))
      .maximumSize(MAX_STATES)
      .<Long, SubscriptionSearchCriteria>build()
      .asMap();

  /**
   * Marks the given user as awaiting a subscription name for the given search criteria.
   *
   * @param telegramId Telegram user identifier, never null
   * @param criteria   the search criteria the new subscription will be created with, never null
   */
  public void await(Long telegramId, SubscriptionSearchCriteria criteria) {
    pending.put(telegramId, criteria);
  }

  /**
   * Checks whether the given user is currently awaiting a subscription name.
   *
   * @param telegramId Telegram user identifier, never null
   * @return true if a subscription-name prompt is pending for this user
   */
  public boolean isAwaitingName(Long telegramId) {
    return pending.containsKey(telegramId);
  }

  /**
   * Returns the pending search criteria for the given user without clearing the pending state.
   *
   * <p>Left in place (not consumed) so an invalid name can be re-prompted without losing the
   * criteria the user is trying to subscribe to.
   *
   * @param telegramId Telegram user identifier, never null
   * @return the pending criteria, or empty if no prompt is pending
   */
  public Optional<SubscriptionSearchCriteria> peek(Long telegramId) {
    return Optional.ofNullable(pending.get(telegramId));
  }

  /**
   * Clears the pending prompt for the given user.
   *
   * @param telegramId Telegram user identifier, never null
   */
  public void clear(Long telegramId) {
    pending.remove(telegramId);
  }
}
