package com.flatio.repository;

import com.flatio.domain.user.AuthProvider;
import com.flatio.domain.user.User;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long>, JpaSpecificationExecutor<User> {

  /**
   * Finds an active user by authentication provider and external identifier.
   *
   * <p>Looks up the user via a UserAuthProvider record matching the given provider and externalId.
   * Only returns users with {@code active = true}.
   *
   * @param provider   the authentication provider (e.g. TELEGRAM, GOOGLE, EMAIL)
   * @param externalId the external identifier assigned by the provider
   * @return the active user if found, or empty
   */
  @Query("SELECT u FROM User u WHERE u.active = true AND EXISTS (" +
      "SELECT p FROM UserAuthProvider p " +
      "WHERE p.user = u AND p.provider = :provider AND p.externalId = :externalId)")
  Optional<User> findByProviderAndExternalId(
      @Param("provider") AuthProvider provider,
      @Param("externalId") String externalId
  );

  /**
   * Finds an active user by their Telegram ID.
   *
   * <p>Convenience method delegating to {@link #findByProviderAndExternalId} with
   * {@link AuthProvider#TELEGRAM}. Only returns users with {@code active = true}.
   *
   * @param telegramId the Telegram user ID stored as externalId in UserAuthProvider
   * @return the active user if found, or empty
   */
  default Optional<User> findByTelegramId(String telegramId) {
    return findByProviderAndExternalId(AuthProvider.TELEGRAM, telegramId);
  }
}
