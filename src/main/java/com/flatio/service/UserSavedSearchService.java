package com.flatio.service;

import com.flatio.service.domain.SearchFilter;
import java.util.Optional;

public interface UserSavedSearchService {

  /**
   * Saves or updates the search filter for a Telegram user.
   *
   * <p>If a saved search already exists for the given user, it is updated in place (UPSERT).
   * Otherwise a new record is created.
   *
   * @param telegramUserId the Telegram user ID, must not be null
   * @param filter         the filter to persist, must not be null
   */
  void save(Long telegramUserId, SearchFilter filter);

  /**
   * Returns the last saved search filter for a Telegram user.
   *
   * @param telegramUserId the Telegram user ID, must not be null
   * @return the saved filter, or empty if the user has never saved a search
   */
  Optional<SearchFilter> getByTelegramUserId(Long telegramUserId);
}
