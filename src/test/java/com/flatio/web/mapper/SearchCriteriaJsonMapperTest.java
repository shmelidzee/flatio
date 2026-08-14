package com.flatio.web.mapper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flatio.domain.listing.DealType;
import com.flatio.web.dto.SubscriptionSearchCriteria;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SearchCriteriaJsonMapperTest {

  private final SearchCriteriaJsonMapper mapper = new SearchCriteriaJsonMapper(new ObjectMapper());

  @Test
  void should_round_trip_criteria_through_map() {
    // Given
    var criteria = new SubscriptionSearchCriteria(
        DealType.RENT, "APARTMENT", "onliner", "Минск", 1L,
        BigDecimal.valueOf(500), BigDecimal.valueOf(1500), 2, "уютная квартира", true
    );

    // When
    var map = mapper.toMap(criteria);
    var roundTripped = mapper.toCriteria(map);

    // Then
    assertThat(roundTripped).isEqualTo(criteria);
  }

  @Test
  void should_produce_map_with_all_field_keys() {
    // Given
    var criteria = new SubscriptionSearchCriteria(
        DealType.SELL, null, null, null, null, null, null, null, null, null
    );

    // When
    var map = mapper.toMap(criteria);

    // Then
    assertThat(map).containsEntry("dealType", "SELL");
  }

  @Test
  void should_return_null_when_map_is_null() {
    // When
    var result = mapper.toCriteria(null);

    // Then
    assertThat(result).isNull();
  }
}
