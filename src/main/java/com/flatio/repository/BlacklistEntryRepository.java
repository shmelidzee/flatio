package com.flatio.repository;

import com.flatio.domain.blacklist.BlacklistEntry;
import com.flatio.domain.blacklist.BlacklistEntryType;
import com.flatio.domain.user.User;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository for {@link BlacklistEntry}.
 *
 * <p>No {@code JOIN FETCH} is used for the paginated list queries: the response mapping reads
 * only {@code id}, {@code type}, {@code value}, and {@code createdAt} off the entity itself and
 * never traverses the lazy {@code user} association, so there is no N+1 risk to guard against.
 */
public interface BlacklistEntryRepository extends JpaRepository<BlacklistEntry, Long> {

  /**
   * Returns a page of blacklist entries owned by the given user.
   *
   * @param user     the owning user
   * @param pageable pagination and sorting configuration
   * @return page of blacklist entries, never null
   */
  Page<BlacklistEntry> findByUser(User user, Pageable pageable);

  /**
   * Returns a page of blacklist entries of the given type owned by the given user.
   *
   * @param user     the owning user
   * @param type     the entry type to filter by
   * @param pageable pagination and sorting configuration
   * @return page of blacklist entries, never null
   */
  Page<BlacklistEntry> findByUserAndType(User user, BlacklistEntryType type, Pageable pageable);

  /**
   * Finds a blacklist entry by owner, type, and value.
   *
   * <p>Used to look up an existing entry before creating a new one, so re-adding the same item is
   * idempotent rather than creating a duplicate.
   *
   * @param user  the expected owner
   * @param type  the entry type
   * @param value the entry value
   * @return the entry if found and owned by {@code user}, or empty
   */
  Optional<BlacklistEntry> findByUserAndTypeAndValue(User user, BlacklistEntryType type, String value);

  /**
   * Finds a blacklist entry by ID, scoped to its owner.
   *
   * <p>Used to enforce that a user can only read or delete their own blacklist entries.
   *
   * @param id   the blacklist entry ID
   * @param user the expected owner
   * @return the entry if found and owned by {@code user}, or empty
   */
  Optional<BlacklistEntry> findByIdAndUser(Long id, User user);

  /**
   * Counts the blacklist entries of the given type owned by the given user.
   *
   * <p>Used to enforce the tariff limit on {@code KEYWORD} entries; {@code LISTING} and
   * {@code SOURCE} entries are not tariff-limited.
   *
   * @param user the owning user
   * @param type the entry type to count
   * @return number of matching entries
   */
  long countByUserAndType(User user, BlacklistEntryType type);

  /**
   * Finds all blacklist entries owned by any of the given users.
   *
   * <p>Used by {@code NotificationTriggerServiceImpl} to batch-preload every evaluable
   * subscription's owner's blacklist in one query per {@code evaluate} run (issue #414), instead
   * of one query per distinct user.
   *
   * @param users the owners to fetch entries for
   * @return all matching blacklist entries, never null, may be empty
   */
  List<BlacklistEntry> findByUserIn(Collection<User> users);
}
