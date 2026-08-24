package com.flatio.service;

import com.flatio.domain.user.UserSavedSearch;
import com.flatio.repository.UserSavedSearchRepository;
import com.flatio.service.domain.SearchFilter;
import com.flatio.service.impl.UserSavedSearchServiceImpl;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserSavedSearchServiceTest {

  @Mock
  private UserSavedSearchRepository userSavedSearchRepository;

  @InjectMocks
  private UserSavedSearchServiceImpl userSavedSearchService;

  @BeforeEach
  void setUp() {
    // save() delegates to saveTransactional() via a self-injected AOP proxy reference (see class
    // Javadoc); pointing it at the same instance preserves real behaviour in these unit tests,
    // which do not exercise Spring's transactional proxying.
    ReflectionTestUtils.setField(userSavedSearchService, "self", userSavedSearchService);
  }

  // -------------------------------------------------------------------------
  // save — create
  // -------------------------------------------------------------------------

  @Test
  void should_create_new_record_when_no_saved_search_exists() {
    // Given
    when(userSavedSearchRepository.findByTelegramUserId(1L)).thenReturn(Optional.empty());
    when(userSavedSearchRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    var filter = buildFilter("BY-MIN", BigDecimal.valueOf(500), BigDecimal.valueOf(2000),
        2, null, "APARTMENT", true, "центр");

    // When
    userSavedSearchService.save(1L, filter);

    // Then
    var captor = ArgumentCaptor.forClass(UserSavedSearch.class);
    verify(userSavedSearchRepository).save(captor.capture());
    var saved = captor.getValue();
    assertThat(saved.getTelegramUserId()).isEqualTo(1L);
    assertThat(saved.getRegionCode()).isEqualTo("BY-MIN");
    assertThat(saved.getPriceMin()).isEqualByComparingTo(BigDecimal.valueOf(500));
    assertThat(saved.getPriceMax()).isEqualByComparingTo(BigDecimal.valueOf(2000));
    assertThat(saved.getRoomsMin()).isEqualTo(2);
    assertThat(saved.getRoomsMax()).isNull();
    assertThat(saved.getPropertyType()).isEqualTo("APARTMENT");
    assertThat(saved.getIsOwner()).isTrue();
    assertThat(saved.getKeyword()).isEqualTo("центр");
  }

  // -------------------------------------------------------------------------
  // save — update (UPSERT)
  // -------------------------------------------------------------------------

  @Test
  void should_update_existing_record_when_saved_search_already_exists() {
    // Given
    var existing = buildEntity(42L, 1L, "BY-MIN", BigDecimal.valueOf(300));
    when(userSavedSearchRepository.findByTelegramUserId(1L)).thenReturn(Optional.of(existing));
    when(userSavedSearchRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    var updatedFilter = buildFilter("BY-GRO", BigDecimal.valueOf(800), BigDecimal.valueOf(3000),
        3, null, null, null, null);

    // When
    userSavedSearchService.save(1L, updatedFilter);

    // Then — same entity mutated, not a fresh one
    var captor = ArgumentCaptor.forClass(UserSavedSearch.class);
    verify(userSavedSearchRepository).save(captor.capture());
    var saved = captor.getValue();
    assertThat(saved.getId()).isEqualTo(42L);
    assertThat(saved.getRegionCode()).isEqualTo("BY-GRO");
    assertThat(saved.getPriceMin()).isEqualByComparingTo(BigDecimal.valueOf(800));
    assertThat(saved.getPropertyType()).isNull();
    assertThat(saved.getIsOwner()).isNull();
  }

  // -------------------------------------------------------------------------
  // save — concurrent write conflict (issue #376)
  // -------------------------------------------------------------------------

  @Test
  void should_retry_and_update_when_concurrent_write_conflict_occurs() {
    // Given — first read finds nothing (race with another save for the same user in flight);
    // the first save violates the UNIQUE(telegram_user_id) constraint the other call already
    // committed, so the retry finds that row and updates it in place instead of inserting again
    var filter = buildFilter("BY-MIN", BigDecimal.valueOf(500), BigDecimal.valueOf(2000),
        2, null, "APARTMENT", true, "центр");
    var racedEntity = buildEntity(5L, 1L, "BY-GRO", BigDecimal.valueOf(100));
    when(userSavedSearchRepository.findByTelegramUserId(1L))
        .thenReturn(Optional.empty())
        .thenReturn(Optional.of(racedEntity));
    when(userSavedSearchRepository.save(any()))
        .thenThrow(new DataIntegrityViolationException(
            "duplicate key value violates unique constraint \"uq_user_saved_searches_telegram_user_id\""))
        .thenAnswer(inv -> inv.getArgument(0));

    // When
    userSavedSearchService.save(1L, filter);

    // Then — second save call updates the row the other call committed
    var captor = ArgumentCaptor.forClass(UserSavedSearch.class);
    verify(userSavedSearchRepository, times(2)).save(captor.capture());
    var retried = captor.getAllValues().get(1);
    assertThat(retried.getId()).isEqualTo(5L);
    assertThat(retried.getRegionCode()).isEqualTo("BY-MIN");
  }

  // -------------------------------------------------------------------------
  // getByTelegramUserId — found
  // -------------------------------------------------------------------------

  @Test
  void should_return_filter_when_saved_search_exists() {
    // Given
    var entity = buildEntity(10L, 2L, "BY-MIN", BigDecimal.valueOf(600));
    entity.setPriceMax(BigDecimal.valueOf(1500));
    entity.setRoomsMin(1);
    entity.setPropertyType("APARTMENT");
    when(userSavedSearchRepository.findByTelegramUserId(2L)).thenReturn(Optional.of(entity));

    // When
    var result = userSavedSearchService.getByTelegramUserId(2L);

    // Then
    assertThat(result).isPresent();
    assertThat(result.get().regionCode()).isEqualTo("BY-MIN");
    assertThat(result.get().priceMin()).isEqualByComparingTo(BigDecimal.valueOf(600));
    assertThat(result.get().priceMax()).isEqualByComparingTo(BigDecimal.valueOf(1500));
    assertThat(result.get().roomsMin()).isEqualTo(1);
    assertThat(result.get().propertyType()).isEqualTo("APARTMENT");
  }

  // -------------------------------------------------------------------------
  // getByTelegramUserId — not found
  // -------------------------------------------------------------------------

  @Test
  void should_return_empty_when_no_saved_search_exists() {
    // Given
    when(userSavedSearchRepository.findByTelegramUserId(99L)).thenReturn(Optional.empty());

    // When
    var result = userSavedSearchService.getByTelegramUserId(99L);

    // Then
    assertThat(result).isEmpty();
  }

  // -------------------------------------------------------------------------
  // helpers
  // -------------------------------------------------------------------------

  private static SearchFilter buildFilter(
      String regionCode, BigDecimal priceMin, BigDecimal priceMax,
      Integer roomsMin, Integer roomsMax, String propertyType, Boolean isOwner, String keyword) {
    return new SearchFilter(regionCode, null, null, priceMin, priceMax, roomsMin, roomsMax,
        propertyType, isOwner, keyword);
  }

  private static UserSavedSearch buildEntity(Long id, Long telegramUserId,
      String regionCode, BigDecimal priceMin) {
    var entity = new UserSavedSearch();
    entity.setId(id);
    entity.setTelegramUserId(telegramUserId);
    entity.setRegionCode(regionCode);
    entity.setPriceMin(priceMin);
    return entity;
  }
}
