package com.flatio.security;

import com.flatio.domain.user.UserRole;
import com.flatio.repository.UserRepository;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Scheduler;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Short-TTL cache of each user's current {@code active} flag and {@link UserRole}, backed by
 * {@link UserRepository}.
 *
 * <p>{@link com.flatio.security.JwtAuthenticationFilter} consults this cache on every
 * authenticated request instead of trusting the {@code active}/role state a JWT captured at
 * issue time (issue #365): a deactivated or role-downgraded user's existing token would
 * otherwise keep working until it expires. A short TTL bounds a database read to once per
 * {@value #TTL_SECONDS} seconds per user rather than once per request, while still revoking
 * access within a few seconds of an admin action instead of up to the full token lifetime.
 *
 * <p>{@link com.flatio.service.impl.AdminUserServiceImpl#update} evicts the affected user's
 * entry immediately after saving, so a status change made through the admin panel takes effect
 * on that user's very next request rather than waiting out the TTL.
 *
 * <p>Backed by Caffeine rather than a plain {@code ConcurrentHashMap} (issue #399) — a plain map
 * only drops an expired entry when that same key is looked up again, so a user who authenticates
 * once and never returns keeps their entry forever, growing the map unboundedly over the
 * application's lifetime. {@link Scheduler#systemScheduler()} makes Caffeine proactively sweep
 * expired entries on a background timer instead of relying solely on that lazy per-key cleanup.
 */
@Component
@RequiredArgsConstructor
public class UserStatusCache {

  private static final long TTL_SECONDS = 30;

  private final UserRepository userRepository;
  private final Map<Long, UserStatus> cache = Caffeine.newBuilder()
      .expireAfterWrite(Duration.ofSeconds(TTL_SECONDS))
      .scheduler(Scheduler.systemScheduler())
      .<Long, UserStatus>build()
      .asMap();

  /**
   * Returns the current active flag and role for the given user, from cache if still fresh
   * or from the database otherwise.
   *
   * @param userId id of the user to look up
   * @return the user's current status, or empty if no such user exists
   */
  public Optional<UserStatus> getStatus(Long userId) {
    var cached = cache.get(userId);
    if (cached != null) {
      return Optional.of(cached);
    }
    return loadAndCache(userId);
  }

  /**
   * Removes any cached entry for the given user so the next lookup reads the database directly.
   *
   * @param userId id of the user whose status just changed
   */
  public void evict(Long userId) {
    cache.remove(userId);
  }

  private Optional<UserStatus> loadAndCache(Long userId) {
    return userRepository.findById(userId).map(user -> {
      var status = new UserStatus(user.isActive(), user.getRole());
      cache.put(userId, status);
      return status;
    });
  }

  /**
   * A user's active flag and role as of the last database read.
   *
   * @param active whether the user's account is currently active
   * @param role   the user's current role
   */
  public record UserStatus(boolean active, UserRole role) {}
}
