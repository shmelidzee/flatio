package com.flatio.repository;

import com.flatio.domain.user.UserSavedSearch;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserSavedSearchRepository extends JpaRepository<UserSavedSearch, Long> {

  /**
   * Returns the saved search for the given Telegram user.
   *
   * @param telegramUserId the Telegram user ID
   * @return the saved search, or empty if the user has not saved any search yet
   */
  Optional<UserSavedSearch> findByTelegramUserId(Long telegramUserId);
}
