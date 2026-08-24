package com.flatio.service.impl;

import com.flatio.domain.user.AuthProvider;
import com.flatio.domain.user.User;
import com.flatio.domain.user.UserAuthProvider;
import com.flatio.repository.UserAuthProviderRepository;
import com.flatio.repository.UserRepository;
import java.time.Instant;
import java.util.Optional;

import com.flatio.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
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

  /**
   * Self-reference injected lazily to ensure the call from {@link #findOrCreate} goes through
   * the Spring AOP proxy, which activates the {@code @Transactional} behaviour on
   * {@link #findOrCreateTransactional}. This lets a failed transaction be rolled back and
   * committed before {@link #findOrCreate} retries in a fresh transaction — required because a
   * constraint violation aborts the whole PostgreSQL transaction, so retrying within the same
   * transaction would fail immediately with "current transaction is aborted".
   */
  @Lazy
  @Autowired
  private UserServiceImpl self;

  @Override
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public User findOrCreate(Long telegramId, String username, String firstName) {
    try {
      return self.findOrCreateTransactional(telegramId, username, firstName);
    } catch (DataIntegrityViolationException e) {
      return retryAfterConflict(telegramId, e);
    }
  }

  @Override
  public Optional<User> findByTelegramId(Long telegramId) {
    return userRepository.findByTelegramId(String.valueOf(telegramId));
  }

  @Transactional
  User findOrCreateTransactional(Long telegramId, String username, String firstName) {
    String externalId = String.valueOf(telegramId);
    return userRepository.findByTelegramId(externalId)
        .map(user -> refreshLastSeen(user))
        .orElseGet(() -> register(telegramId, externalId, username, firstName));
  }

  /**
   * Handles a concurrent registration race: two parallel {@code /start} calls for the same new
   * Telegram user both see no existing row and both attempt to insert a
   * {@code user_auth_provider} record, which is unique on {@code (provider, external_id)}. The
   * loser's insert fails with {@link DataIntegrityViolationException} — its counterpart has
   * already committed the row by then, so a fresh lookup returns it.
   *
   * @param telegramId the Telegram user ID being registered
   * @param cause      the conflict that triggered this retry, rethrown if the row is still absent
   * @return the user registered by the winning concurrent call
   */
  private User retryAfterConflict(Long telegramId, DataIntegrityViolationException cause) {
    log.debug("Concurrent user registration conflict, retrying findByTelegramId: telegramId={}", telegramId);
    return userRepository.findByTelegramId(String.valueOf(telegramId))
        .orElseThrow(() -> cause);
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
