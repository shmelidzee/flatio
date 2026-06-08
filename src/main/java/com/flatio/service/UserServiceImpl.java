package com.flatio.service;

import com.flatio.domain.user.AuthProvider;
import com.flatio.domain.user.User;
import com.flatio.domain.user.UserAuthProvider;
import com.flatio.repository.UserAuthProviderRepository;
import com.flatio.repository.UserRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementation of {@link UserService} handling user lifecycle.
 */
@Service
@Slf4j
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

  private final UserRepository userRepository;
  private final UserAuthProviderRepository userAuthProviderRepository;

  @Override
  @Transactional
  public User findOrCreate(Long telegramId, String username, String firstName) {
    String externalId = String.valueOf(telegramId);
    return userRepository.findByTelegramId(externalId)
        .map(user -> refreshLastSeen(user))
        .orElseGet(() -> register(telegramId, externalId, username, firstName));
  }

  private User refreshLastSeen(User user) {
    user.setLastSeen(Instant.now());
    return userRepository.save(user);
  }

  private User register(Long telegramId, String externalId, String username, String firstName) {
    var user = new User();
    user.setDisplayName(resolveDisplayName(firstName, username));
    user.setActive(true);
    user.setLastSeen(Instant.now());
    userRepository.save(user);

    var authProvider = new UserAuthProvider();
    authProvider.setUser(user);
    authProvider.setProvider(AuthProvider.TELEGRAM);
    authProvider.setExternalId(externalId);
    authProvider.setTelegramUsername(username);
    userAuthProviderRepository.save(authProvider);

    log.info("User registered: telegramId={}", telegramId);
    return user;
  }

  private String resolveDisplayName(String firstName, String username) {
    if (firstName != null && !firstName.isBlank()) {
      return firstName;
    }
    if (username != null && !username.isBlank()) {
      return "@" + username;
    }
    return "Telegram User";
  }
}
