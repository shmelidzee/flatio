package com.flatio.repository;

import com.flatio.domain.user.AuthProvider;
import com.flatio.domain.user.User;
import com.flatio.domain.user.UserAuthProvider;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository for {@link UserAuthProvider} entities.
 */
public interface UserAuthProviderRepository extends JpaRepository<UserAuthProvider, Long> {

  /**
   * Finds a user's link to the given auth provider.
   *
   * <p>For {@link AuthProvider#TELEGRAM}, {@code externalId} is the user's Telegram chat ID,
   * used by {@code TelegramNotificationSender} to deliver a notification without going through
   * an inbound Telegram update.
   *
   * @param user     the user to look up
   * @param provider the auth provider
   * @return the link if the user has one for this provider, or empty
   */
  Optional<UserAuthProvider> findByUserAndProvider(User user, AuthProvider provider);
}
