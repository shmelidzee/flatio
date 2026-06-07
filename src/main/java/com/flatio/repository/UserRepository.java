package com.flatio.repository;

import com.flatio.domain.user.User;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long> {

  /**
   * Finds a user by their Telegram ID.
   *
   * <p>Looks up the user via a UserAuthProvider record with provider TELEGRAM
   * and the given externalId. Returns empty if no such provider record exists.
   *
   * @param telegramId the Telegram user ID stored as externalId in UserAuthProvider
   * @return the user if found, or empty
   */
  @Query("SELECT u FROM User u WHERE EXISTS (" +
      "SELECT p FROM UserAuthProvider p " +
      "WHERE p.user = u " +
      "AND p.provider = com.flatio.domain.user.AuthProvider.TELEGRAM " +
      "AND p.externalId = :telegramId)")
  Optional<User> findByTelegramId(@Param("telegramId") String telegramId);
}
