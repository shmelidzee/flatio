package com.flatio.service.impl;

import com.flatio.domain.user.UserSavedSearch;
import com.flatio.repository.UserSavedSearchRepository;
import com.flatio.service.UserSavedSearchService;
import com.flatio.service.domain.SearchFilter;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@Slf4j
@RequiredArgsConstructor
public class UserSavedSearchServiceImpl implements UserSavedSearchService {

  private final UserSavedSearchRepository userSavedSearchRepository;

  @Override
  @Transactional
  public void save(Long telegramUserId, SearchFilter filter) {
    UserSavedSearch entity = userSavedSearchRepository
        .findByTelegramUserId(telegramUserId)
        .orElseGet(UserSavedSearch::new);
    entity.setTelegramUserId(telegramUserId);
    applyFilter(entity, filter);
    userSavedSearchRepository.save(entity);
    log.debug("Saved search filter for telegramUserId={}", telegramUserId);
  }

  @Override
  public Optional<SearchFilter> getByTelegramUserId(Long telegramUserId) {
    return userSavedSearchRepository
        .findByTelegramUserId(telegramUserId)
        .map(this::toFilter);
  }

  private void applyFilter(UserSavedSearch entity, SearchFilter filter) {
    entity.setRegionCode(filter.regionCode());
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
