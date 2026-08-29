package com.flatio.telegram.state;

import com.flatio.web.dto.SubscriptionResponse;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Tracks, per Telegram user, the subscription currently being edited through the search filter
 * wizard (issue #479).
 *
 * <p>The wizard only collects search criteria (deal type, city, price, rooms, ...) — it has no
 * steps for a subscription's name, triggers, or delivery settings. This state holds the original
 * {@link SubscriptionResponse} snapshot so those fields can be carried over unchanged when the
 * edited criteria are saved via {@code SubscriptionService#update}, which replaces the whole
 * request rather than patching individual fields.
 *
 * <p>Mirrors the lightweight Caffeine-backed state pattern used by {@link SubscriptionCreationState}.
 */
@Component
public class SubscriptionEditState {

  private static final long STATE_TTL_MINUTES = 30;
  private static final long MAX_STATES = 10_000;

  private final Map<Long, SubscriptionResponse> editing = Caffeine.newBuilder()
      .expireAfterWrite(Duration.ofMinutes(STATE_TTL_MINUTES))
      .maximumSize(MAX_STATES)
      .<Long, SubscriptionResponse>build()
      .asMap();

  /**
   * Marks the given user as editing the given subscription.
   *
   * @param telegramId   Telegram user identifier, never null
   * @param subscription snapshot of the subscription being edited, never null
   */
  public void start(Long telegramId, SubscriptionResponse subscription) {
    editing.put(telegramId, subscription);
  }

  /**
   * Returns the subscription snapshot the given user is currently editing, if any.
   *
   * @param telegramId Telegram user identifier, never null
   * @return the subscription snapshot, or empty if the user is not editing one
   */
  public Optional<SubscriptionResponse> get(Long telegramId) {
    return Optional.ofNullable(editing.get(telegramId));
  }

  /**
   * Clears the edit state for the given user.
   *
   * @param telegramId Telegram user identifier, never null
   */
  public void clear(Long telegramId) {
    editing.remove(telegramId);
  }
}
