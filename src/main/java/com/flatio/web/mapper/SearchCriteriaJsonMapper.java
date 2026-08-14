package com.flatio.web.mapper;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flatio.web.dto.SubscriptionSearchCriteria;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Converts between {@link SubscriptionSearchCriteria} and the {@code Map<String, Object>}
 * representation stored as JSON on {@code Subscription.searchCriteria}.
 *
 * <p>The domain entity stores the filter as a generic JSON document rather than depending on
 * a web-layer DTO type, so this conversion happens at the mapper boundary.
 */
@Component
@RequiredArgsConstructor
public class SearchCriteriaJsonMapper {

  private final ObjectMapper objectMapper;

  /**
   * Converts a search criteria DTO to a JSON-compatible map for entity storage.
   *
   * @param criteria the search criteria, must not be null
   * @return map representation suitable for a jsonb column
   */
  public Map<String, Object> toMap(SubscriptionSearchCriteria criteria) {
    return objectMapper.convertValue(criteria, new TypeReference<Map<String, Object>>() {});
  }

  /**
   * Converts a stored JSON map back to a search criteria DTO.
   *
   * @param map the map read from the {@code search_criteria} jsonb column
   * @return the reconstructed search criteria, or null if {@code map} is null
   */
  public SubscriptionSearchCriteria toCriteria(Map<String, Object> map) {
    if (map == null) {
      return null;
    }
    return objectMapper.convertValue(map, SubscriptionSearchCriteria.class);
  }
}
