package com.flatio.service;

import com.flatio.domain.user.User;

/**
 * Service for managing platform users.
 */
public interface UserService {

  /**
   * Finds an existing user by Telegram ID, or creates a new one on first interaction.
   *
   * <p>On every call the user's {@code lastSeen} timestamp is updated.
   * A new {@link com.flatio.domain.user.UserAuthProvider} record is created only on first call.
   *
   * @param telegramId Telegram user ID (numeric)
   * @param username   Telegram username handle, may be null
   * @param firstName  Telegram first name, may be null
   * @return the persisted user, never null
   */
  User findOrCreate(Long telegramId, String username, String firstName);
}
