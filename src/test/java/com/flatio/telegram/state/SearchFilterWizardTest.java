package com.flatio.telegram.state;

import com.flatio.domain.city.City;
import com.flatio.domain.listing.DealType;
import com.flatio.repository.CityRepository;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SearchFilterWizardTest {

  @Mock
  private CityRepository cityRepository;

  private SearchFilterWizard wizard;

  @BeforeEach
  void setUp() {
    wizard = new SearchFilterWizard(cityRepository);
  }

  @Test
  void should_return_empty_state_when_wizard_not_started() {
    // When
    var result = wizard.getState(42L);

    // Then
    assertThat(result).isEmpty();
  }

  @Test
  void should_create_fresh_state_at_deal_type_step_when_started() {
    // When
    var state = wizard.start(1L);

    // Then
    assertThat(state.getCurrentStep()).isEqualTo(FilterStep.DEAL_TYPE);
    assertThat(state.getDealType()).isNull();
    assertThat(state.getPropertyType()).isNull();
    assertThat(state.getRooms()).isNull();
    assertThat(state.getPriceMin()).isNull();
    assertThat(state.getPriceMax()).isNull();
  }

  @Test
  void should_return_state_when_wizard_started() {
    // Given
    wizard.start(1L);

    // When
    var result = wizard.getState(1L);

    // Then
    assertThat(result).isPresent();
  }

  @Test
  void should_advance_to_property_type_when_deal_type_selected() {
    // Given
    wizard.start(1L);

    // When
    var state = wizard.applySelection(1L, FilterStep.DEAL_TYPE, "RENT");

    // Then
    assertThat(state.getDealType()).isEqualTo(DealType.RENT);
    assertThat(state.getCurrentStep()).isEqualTo(FilterStep.PROPERTY_TYPE);
  }

  @Test
  void should_set_null_deal_type_when_any_selected() {
    // Given
    wizard.start(1L);

    // When
    var state = wizard.applySelection(1L, FilterStep.DEAL_TYPE, "ANY");

    // Then
    assertThat(state.getDealType()).isNull();
    assertThat(state.getCurrentStep()).isEqualTo(FilterStep.PROPERTY_TYPE);
  }

  @Test
  void should_advance_to_rooms_when_property_type_selected() {
    // Given
    wizard.start(1L);
    wizard.applySelection(1L, FilterStep.DEAL_TYPE, "SELL");

    // When
    var state = wizard.applySelection(1L, FilterStep.PROPERTY_TYPE, "APARTMENT");

    // Then
    assertThat(state.getPropertyType()).isEqualTo("APARTMENT");
    assertThat(state.getCurrentStep()).isEqualTo(FilterStep.ROOMS);
  }

  @Test
  void should_set_null_property_type_when_any_selected() {
    // Given
    wizard.start(1L);
    wizard.applySelection(1L, FilterStep.DEAL_TYPE, "RENT");

    // When
    var state = wizard.applySelection(1L, FilterStep.PROPERTY_TYPE, "ANY");

    // Then
    assertThat(state.getPropertyType()).isNull();
    assertThat(state.getCurrentStep()).isEqualTo(FilterStep.ROOMS);
  }

  @Test
  void should_advance_to_price_when_rooms_selected() {
    // Given
    wizard.start(1L);
    wizard.applySelection(1L, FilterStep.DEAL_TYPE, "RENT");
    wizard.applySelection(1L, FilterStep.PROPERTY_TYPE, "APARTMENT");

    // When
    var state = wizard.applySelection(1L, FilterStep.ROOMS, "2");

    // Then
    assertThat(state.getRooms()).isEqualTo(2);
    assertThat(state.getCurrentStep()).isEqualTo(FilterStep.PRICE);
  }

  @Test
  void should_set_rooms_four_when_4_plus_selected() {
    // Given
    wizard.start(1L);
    wizard.applySelection(1L, FilterStep.DEAL_TYPE, "RENT");
    wizard.applySelection(1L, FilterStep.PROPERTY_TYPE, "ANY");

    // When
    var state = wizard.applySelection(1L, FilterStep.ROOMS, "4_PLUS");

    // Then
    assertThat(state.getRooms()).isEqualTo(4);
  }

  @Test
  void should_set_null_rooms_when_any_selected() {
    // Given
    wizard.start(1L);
    wizard.applySelection(1L, FilterStep.DEAL_TYPE, "RENT");
    wizard.applySelection(1L, FilterStep.PROPERTY_TYPE, "ANY");

    // When
    var state = wizard.applySelection(1L, FilterStep.ROOMS, "ANY");

    // Then
    assertThat(state.getRooms()).isNull();
  }

  @Test
  void should_advance_to_owner_only_with_low_price_range() {
    // Given
    wizard.start(1L);
    wizard.applySelection(1L, FilterStep.DEAL_TYPE, "RENT");
    wizard.applySelection(1L, FilterStep.PROPERTY_TYPE, "APARTMENT");
    wizard.applySelection(1L, FilterStep.ROOMS, "1");

    // When
    var state = wizard.applySelection(1L, FilterStep.PRICE, "LOW");

    // Then
    assertThat(state.getPriceMin()).isNull();
    assertThat(state.getPriceMax()).isEqualByComparingTo(BigDecimal.valueOf(1_000));
    assertThat(state.getCurrentStep()).isEqualTo(FilterStep.OWNER_ONLY);
  }

  @Test
  void should_set_medium_price_range() {
    // Given
    wizard.start(1L);
    wizard.applySelection(1L, FilterStep.DEAL_TYPE, "RENT");
    wizard.applySelection(1L, FilterStep.PROPERTY_TYPE, "ANY");
    wizard.applySelection(1L, FilterStep.ROOMS, "ANY");

    // When
    var state = wizard.applySelection(1L, FilterStep.PRICE, "MEDIUM");

    // Then
    assertThat(state.getPriceMin()).isEqualByComparingTo(BigDecimal.valueOf(1_000));
    assertThat(state.getPriceMax()).isEqualByComparingTo(BigDecimal.valueOf(2_000));
  }

  @Test
  void should_set_high_price_range() {
    // Given
    wizard.start(1L);
    wizard.applySelection(1L, FilterStep.DEAL_TYPE, "RENT");
    wizard.applySelection(1L, FilterStep.PROPERTY_TYPE, "ANY");
    wizard.applySelection(1L, FilterStep.ROOMS, "ANY");

    // When
    var state = wizard.applySelection(1L, FilterStep.PRICE, "HIGH");

    // Then
    assertThat(state.getPriceMin()).isEqualByComparingTo(BigDecimal.valueOf(2_000));
    assertThat(state.getPriceMax()).isEqualByComparingTo(BigDecimal.valueOf(4_000));
  }

  @Test
  void should_set_premium_price_range() {
    // Given
    wizard.start(1L);
    wizard.applySelection(1L, FilterStep.DEAL_TYPE, "RENT");
    wizard.applySelection(1L, FilterStep.PROPERTY_TYPE, "ANY");
    wizard.applySelection(1L, FilterStep.ROOMS, "ANY");

    // When
    var state = wizard.applySelection(1L, FilterStep.PRICE, "PREMIUM");

    // Then
    assertThat(state.getPriceMin()).isEqualByComparingTo(BigDecimal.valueOf(4_000));
    assertThat(state.getPriceMax()).isNull();
  }

  @Test
  void should_clear_price_when_any_selected() {
    // Given
    wizard.start(1L);
    wizard.applySelection(1L, FilterStep.DEAL_TYPE, "RENT");
    wizard.applySelection(1L, FilterStep.PROPERTY_TYPE, "ANY");
    wizard.applySelection(1L, FilterStep.ROOMS, "ANY");

    // When
    var state = wizard.applySelection(1L, FilterStep.PRICE, "ANY");

    // Then
    assertThat(state.getPriceMin()).isNull();
    assertThat(state.getPriceMax()).isNull();
    assertThat(state.getCurrentStep()).isEqualTo(FilterStep.OWNER_ONLY);
  }

  @Test
  void should_advance_to_city_step_when_owner_only_selected() {
    // Given
    wizard.start(1L);
    wizard.applySelection(1L, FilterStep.DEAL_TYPE, "SELL");
    wizard.applySelection(1L, FilterStep.PROPERTY_TYPE, "ANY");
    wizard.applySelection(1L, FilterStep.ROOMS, "ANY");
    wizard.applySelection(1L, FilterStep.PRICE, "ANY");

    // When
    var state = wizard.applySelection(1L, FilterStep.OWNER_ONLY, "true");

    // Then
    assertThat(state.getOwnerOnly()).isTrue();
    assertThat(state.getCurrentStep()).isEqualTo(FilterStep.CITY);
  }

  @Test
  void should_set_owner_only_null_when_any_selected() {
    // Given
    wizard.start(1L);
    wizard.applySelection(1L, FilterStep.DEAL_TYPE, "SELL");
    wizard.applySelection(1L, FilterStep.PROPERTY_TYPE, "ANY");
    wizard.applySelection(1L, FilterStep.ROOMS, "ANY");
    wizard.applySelection(1L, FilterStep.PRICE, "ANY");

    // When
    var state = wizard.applySelection(1L, FilterStep.OWNER_ONLY, "ANY");

    // Then
    assertThat(state.getOwnerOnly()).isNull();
    assertThat(state.getCurrentStep()).isEqualTo(FilterStep.CITY);
  }

  @Test
  void should_set_city_id_and_advance_to_keyword_when_city_selected() {
    // Given
    wizard.start(1L);
    wizard.applySelection(1L, FilterStep.DEAL_TYPE, "RENT");
    wizard.applySelection(1L, FilterStep.PROPERTY_TYPE, "ANY");
    wizard.applySelection(1L, FilterStep.ROOMS, "ANY");
    wizard.applySelection(1L, FilterStep.PRICE, "ANY");
    wizard.applySelection(1L, FilterStep.OWNER_ONLY, "ANY");

    // When
    var state = wizard.applySelection(1L, FilterStep.CITY, "42");

    // Then
    assertThat(state.getCityId()).isEqualTo(42L);
    assertThat(state.getCurrentStep()).isEqualTo(FilterStep.KEYWORD);
  }

  @Test
  void should_skip_city_step_when_any_selected() {
    // Given
    wizard.start(1L);
    wizard.applySelection(1L, FilterStep.DEAL_TYPE, "RENT");
    wizard.applySelection(1L, FilterStep.PROPERTY_TYPE, "ANY");
    wizard.applySelection(1L, FilterStep.ROOMS, "ANY");
    wizard.applySelection(1L, FilterStep.PRICE, "ANY");
    wizard.applySelection(1L, FilterStep.OWNER_ONLY, "ANY");

    // When
    var state = wizard.applySelection(1L, FilterStep.CITY, "ANY");

    // Then
    assertThat(state.getCityId()).isNull();
    assertThat(state.getCurrentStep()).isEqualTo(FilterStep.KEYWORD);
  }

  @Test
  void should_set_city_by_geolocation_and_advance_to_keyword() {
    // Given
    var city = new City();
    city.setId(5L);
    city.setNameRu("Минск");
    when(cityRepository.findNearestCity(any(), any())).thenReturn(Optional.of(city));
    wizard.start(1L);

    // When
    var state = wizard.applyCityByLocation(1L, BigDecimal.valueOf(53.9), BigDecimal.valueOf(27.5));

    // Then
    assertThat(state.getCityId()).isEqualTo(5L);
    assertThat(state.getCurrentStep()).isEqualTo(FilterStep.KEYWORD);
  }

  @Test
  void should_leave_city_null_when_no_city_found_by_geolocation() {
    // Given
    when(cityRepository.findNearestCity(any(), any())).thenReturn(Optional.empty());
    wizard.start(1L);

    // When
    var state = wizard.applyCityByLocation(1L, BigDecimal.valueOf(53.9), BigDecimal.valueOf(27.5));

    // Then
    assertThat(state.getCityId()).isNull();
    assertThat(state.getCurrentStep()).isEqualTo(FilterStep.KEYWORD);
  }

  @Test
  void should_return_to_deal_type_when_back_pressed_at_property_type() {
    // Given
    wizard.start(1L);
    wizard.applySelection(1L, FilterStep.DEAL_TYPE, "RENT");

    // When
    var state = wizard.stepBack(1L);

    // Then
    assertThat(state.getCurrentStep()).isEqualTo(FilterStep.DEAL_TYPE);
    assertThat(state.getDealType()).isNull();
  }

  @Test
  void should_return_to_property_type_when_back_pressed_at_rooms() {
    // Given
    wizard.start(1L);
    wizard.applySelection(1L, FilterStep.DEAL_TYPE, "RENT");
    wizard.applySelection(1L, FilterStep.PROPERTY_TYPE, "APARTMENT");

    // When
    var state = wizard.stepBack(1L);

    // Then
    assertThat(state.getCurrentStep()).isEqualTo(FilterStep.PROPERTY_TYPE);
    assertThat(state.getPropertyType()).isNull();
  }

  @Test
  void should_return_to_rooms_when_back_pressed_at_price() {
    // Given
    wizard.start(1L);
    wizard.applySelection(1L, FilterStep.DEAL_TYPE, "RENT");
    wizard.applySelection(1L, FilterStep.PROPERTY_TYPE, "ANY");
    wizard.applySelection(1L, FilterStep.ROOMS, "2");

    // When
    var state = wizard.stepBack(1L);

    // Then
    assertThat(state.getCurrentStep()).isEqualTo(FilterStep.ROOMS);
    assertThat(state.getRooms()).isNull();
  }

  @Test
  void should_return_to_price_when_back_pressed_at_owner_only() {
    // Given
    wizard.start(1L);
    wizard.applySelection(1L, FilterStep.DEAL_TYPE, "RENT");
    wizard.applySelection(1L, FilterStep.PROPERTY_TYPE, "ANY");
    wizard.applySelection(1L, FilterStep.ROOMS, "ANY");
    wizard.applySelection(1L, FilterStep.PRICE, "MEDIUM");

    // When
    var state = wizard.stepBack(1L);

    // Then
    assertThat(state.getCurrentStep()).isEqualTo(FilterStep.PRICE);
    assertThat(state.getPriceMin()).isNull();
    assertThat(state.getPriceMax()).isNull();
  }

  @Test
  void should_return_to_owner_only_when_back_pressed_at_city() {
    // Given
    wizard.start(1L);
    wizard.applySelection(1L, FilterStep.DEAL_TYPE, "SELL");
    wizard.applySelection(1L, FilterStep.PROPERTY_TYPE, "ANY");
    wizard.applySelection(1L, FilterStep.ROOMS, "ANY");
    wizard.applySelection(1L, FilterStep.PRICE, "ANY");
    wizard.applySelection(1L, FilterStep.OWNER_ONLY, "true");

    // When
    var state = wizard.stepBack(1L);

    // Then
    assertThat(state.getCurrentStep()).isEqualTo(FilterStep.OWNER_ONLY);
    assertThat(state.getOwnerOnly()).isNull();
  }

  @Test
  void should_return_to_city_when_back_pressed_at_keyword() {
    // Given
    wizard.start(1L);
    wizard.applySelection(1L, FilterStep.DEAL_TYPE, "SELL");
    wizard.applySelection(1L, FilterStep.PROPERTY_TYPE, "ANY");
    wizard.applySelection(1L, FilterStep.ROOMS, "ANY");
    wizard.applySelection(1L, FilterStep.PRICE, "ANY");
    wizard.applySelection(1L, FilterStep.OWNER_ONLY, "true");
    wizard.applySelection(1L, FilterStep.CITY, "1");

    // When
    var state = wizard.stepBack(1L);

    // Then
    assertThat(state.getCurrentStep()).isEqualTo(FilterStep.CITY);
    assertThat(state.getCityId()).isNull();
  }

  @Test
  void should_return_to_owner_only_when_back_pressed_at_done() {
    // Given
    wizard.start(1L);
    wizard.applySelection(1L, FilterStep.DEAL_TYPE, "SELL");
    wizard.applySelection(1L, FilterStep.PROPERTY_TYPE, "ANY");
    wizard.applySelection(1L, FilterStep.ROOMS, "ANY");
    wizard.applySelection(1L, FilterStep.PRICE, "ANY");
    wizard.applySelection(1L, FilterStep.OWNER_ONLY, "true");
    wizard.applySelection(1L, FilterStep.CITY, "ANY");
    wizard.applyKeyword(1L, "тест");

    // When
    var state = wizard.stepBack(1L);

    // Then
    assertThat(state.getCurrentStep()).isEqualTo(FilterStep.KEYWORD);
    assertThat(state.getQuery()).isNull();
  }

  @Test
  void should_restart_when_back_pressed_at_first_step() {
    // Given
    wizard.start(1L);

    // When
    var state = wizard.stepBack(1L);

    // Then
    assertThat(state.getCurrentStep()).isEqualTo(FilterStep.DEAL_TYPE);
    assertThat(wizard.getState(1L)).isPresent();
  }

  @Test
  void should_remove_state_when_reset_called() {
    // Given
    wizard.start(1L);

    // When
    wizard.reset(1L);

    // Then
    assertThat(wizard.getState(1L)).isEmpty();
  }

  @Test
  void should_not_throw_when_unknown_deal_type_value_received() {
    // Given
    wizard.start(1L);

    // When
    var state = wizard.applySelection(1L, FilterStep.DEAL_TYPE, "UNKNOWN_TYPE");

    // Then
    assertThat(state.getDealType()).isNull();
    assertThat(state.getCurrentStep()).isEqualTo(FilterStep.PROPERTY_TYPE);
  }

  @Test
  void should_set_null_property_type_when_unknown_value_received() {
    // Given
    wizard.start(1L);
    wizard.applySelection(1L, FilterStep.DEAL_TYPE, "RENT");

    // When
    var state = wizard.applySelection(1L, FilterStep.PROPERTY_TYPE, "UNKNOWN_TYPE");

    // Then
    assertThat(state.getPropertyType()).isNull();
    assertThat(state.getCurrentStep()).isEqualTo(FilterStep.ROOMS);
  }

  @Test
  void should_not_throw_when_unknown_price_range_received() {
    // Given
    wizard.start(1L);
    wizard.applySelection(1L, FilterStep.DEAL_TYPE, "RENT");
    wizard.applySelection(1L, FilterStep.PROPERTY_TYPE, "ANY");
    wizard.applySelection(1L, FilterStep.ROOMS, "ANY");

    // When
    var state = wizard.applySelection(1L, FilterStep.PRICE, "NONEXISTENT");

    // Then
    assertThat(state.getPriceMin()).isNull();
    assertThat(state.getPriceMax()).isNull();
    assertThat(state.getCurrentStep()).isEqualTo(FilterStep.OWNER_ONLY);
  }
}
