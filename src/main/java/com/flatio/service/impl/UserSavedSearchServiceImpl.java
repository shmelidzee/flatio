package com.flatio.service.impl;

import com.flatio.domain.user.UserSavedSearch;
import com.flatio.repository.UserSavedSearchRepository;
import com.flatio.service.UserSavedSearchService;
import com.flatio.service.domain.SearchFilter;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@Slf4j
@RequiredArgsConstructor
public class UserSavedSearchServiceImpl implements UserSavedSearchService {

  private final UserSavedSearchRepository userSavedSearchRepository;

  /**
   * Self-reference injected lazily so {@link #save} calls {@link #saveTransactional} through the
   * Spring AOP proxy, letting a failed attempt's transaction roll back and commit before retrying
   * in a fresh transaction — required because a constraint violation aborts the whole PostgreSQL
   * transaction, so retrying within the same transaction would fail immediately.
   */
  @Lazy
  @Autowired
  private UserSavedSearchServiceImpl self;

  @Override
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public void save(Long telegramUserId, SearchFilter filter) {
    try {
      self.saveTransactional(telegramUserId, filter);
    } catch (DataIntegrityViolationException e) {
      retryAfterConflict(telegramUserId, filter);
    }
  }

  @Transactional
  void saveTransactional(Long telegramUserId, SearchFilter filter) {
    UserSavedSearch entity = userSavedSearchRepository
        .findByTelegramUserId(telegramUserId)
        .orElseGet(UserSavedSearch::new);
    entity.setTelegramUserId(telegramUserId);
    applyFilter(entity, filter);
    userSavedSearchRepository.save(entity);
    log.debug("Saved search filter for telegramUserId={}", telegramUserId);
  }

  /**
   * Handles a concurrent write race on the same {@code telegramUserId}: two rapid filter changes
   * from the same user both see no existing row (or the same version) and both attempt to insert
   * or update it, violating the {@code UNIQUE(telegram_user_id)} constraint. Retrying finds the
   * row the other call already committed and updates it in place.
   *
   * @param telegramUserId the Telegram user ID whose saved search is being written
   * @param filter         the filter to persist on retry
   */
  private void retryAfterConflict(Long telegramUserId, SearchFilter filter) {
    log.debug("Concurrent saved-search write conflict, retrying: telegramUserId={}", telegramUserId);
    self.saveTransactional(telegramUserId, filter);
  }

  @Override
  public Optional<SearchFilter> getByTelegramUserId(Long telegramUserId) {
    return userSavedSearchRepository
        .findByTelegramUserId(telegramUserId)
        .map(this::toFilter);
  }

  private void applyFilter(UserSavedSearch entity, SearchFilter filter) {
    entity.setRegionCode(filter.regionCode());
    entity.setDealType(filter.dealType());
    entity.setCityId(filter.cityId());
    entity.setPriceMin(filter.priceMin());
    entity.setPriceMax(filter.priceMax());
    entity.setRoomsMin(filter.roomsMin());
    entity.setRoomsMax(filter.roomsMax());
    entity.setPropertyType(filter.propertyType());
    entity.setIsOwner(filter.isOwner());
    entity.setKeyword(filter.keyword());
  }

  private SearchFilter toFilter(UserSavedSearch entity) {
    return new SearchFilter(
        entity.getRegionCode(),
        entity.getDealType(),
        entity.getCityId(),
        entity.getPriceMin(),
        entity.getPriceMax(),
        entity.getRoomsMin(),
        entity.getRoomsMax(),
        entity.getPropertyType(),
        entity.getIsOwner(),
        entity.getKeyword()
    );
  }
}
