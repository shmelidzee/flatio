package com.flatio.service.notification;

import com.flatio.domain.user.AuthProvider;
import com.flatio.domain.user.User;
import com.flatio.domain.user.UserAuthProvider;
import com.flatio.repository.UserAuthProviderRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Resolves a user's Telegram chat ID for outbound delivery.
 *
 * <p>Extracted so {@code TelegramNotificationSender} (REALTIME) and {@code
 * BatchNotificationSender} (DIGEST/DAILY, issue #410) share one lookup instead of duplicating it.
 */
@Component
@RequiredArgsConstructor
public class TelegramChatResolver {

  private final UserAuthProviderRepository userAuthProviderRepository;

  /**
   * Resolves the given user's Telegram chat ID.
   *
   * @param user the notification recipient, never null
   * @return the linked Telegram chat ID, or empty if no Telegram account is linked
   */
  public Optional<String> resolveChatId(User user) {
    return userAuthProviderRepository.findByUserAndProvider(user, AuthProvider.TELEGRAM)
        .map(UserAuthProvider::getExternalId);
  }
}
