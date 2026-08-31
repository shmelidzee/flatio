package com.flatio.telegram.keyboard;

import com.flatio.domain.city.City;
import com.flatio.domain.listing.DealType;
import com.flatio.config.SellPriceFilterProperties;
import com.flatio.service.CityService;
import com.flatio.telegram.state.FilterStep;
import com.flatio.telegram.state.SearchFilterState;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FilterKeyboardFactoryTest {

  private static final SellPriceFilterProperties DEFAULT_PROPS = new SellPriceFilterProperties(
      BigDecimal.valueOf(100_000),
      BigDecimal.valueOf(200_000),
      BigDecimal.valueOf(400_000)
  );

  @Mock
  private CityService cityService;

  private FilterKeyboardFactory factory;

  @BeforeEach
  void setUp() {
    factory = new FilterKeyboardFactory(DEFAULT_PROPS, cityService);
  }

  private static City buildCity(Long id, String nameRu) {
    var city = new City();
    city.setId(id);
    city.setNameRu(nameRu);
    return city;
  }

  // -------------------------------------------------------------------------
  // SELL price keyboard — dynamic labels from config
  // -------------------------------------------------------------------------

  @Test
  void should_show_sell_price_labels_from_config_when_deal_type_is_sell() {
    // Given
    var state = new SearchFilterState();
    state.setCurrentStep(FilterStep.PRICE);
    state.setDealType(DealType.SELL);

    // When
    InlineKeyboardMarkup keyboard = factory.buildForStep(state);
    List<String> labels = extractButtonLabels(keyboard);

    // Then — labels reflect configured sell thresholds
    assertThat(labels).contains("до 100 000");
    assertThat(labels).contains("100 000–200 000");
    assertThat(labels).contains("200 000–400 000");
    assertThat(labels).contains("400 000+");
  }

  @Test
  void should_show_rent_price_labels_when_deal_type_is_rent() {
    // Given
    var state = new SearchFilterState();
    state.setCurrentStep(FilterStep.PRICE);
    state.setDealType(DealType.RENT);

    // When
    InlineKeyboardMarkup keyboard = factory.buildForStep(state);
    List<String> labels = extractButtonLabels(keyboard);

    // Then — rent labels stay static
    assertThat(labels).contains("до 1 000");
    assertThat(labels).contains("1 000–2 000");
    assertThat(labels).contains("2 000–4 000");
    assertThat(labels).contains("4 000+");
  }

  @Test
  void should_show_sell_price_labels_from_custom_config() {
    // Given — different market with lower price tiers
    var customProps = new SellPriceFilterProperties(
        BigDecimal.valueOf(50_000),
        BigDecimal.valueOf(100_000),
        BigDecimal.valueOf(200_000)
    );
    var customFactory = new FilterKeyboardFactory(customProps, cityService);
    var state = new SearchFilterState();
    state.setCurrentStep(FilterStep.PRICE);
    state.setDealType(DealType.SELL);

    // When
    InlineKeyboardMarkup keyboard = customFactory.buildForStep(state);
    List<String> labels = extractButtonLabels(keyboard);

    // Then — reflects custom config, not defaults
    assertThat(labels).contains("до 50 000");
    assertThat(labels).contains("50 000–100 000");
    assertThat(labels).contains("100 000–200 000");
    assertThat(labels).contains("200 000+");
  }

  @Test
  void should_show_sell_price_prompt_when_deal_type_is_sell() {
    // Given
    var state = new SearchFilterState();
    state.setCurrentStep(FilterStep.PRICE);
    state.setDealType(DealType.SELL);

    // When
    String text = factory.getStepText(state);

    // Then
    assertThat(text).isEqualTo("💰 Диапазон цены (BYN):");
  }

  @Test
  void should_show_rent_price_prompt_when_deal_type_is_rent() {
    // Given
    var state = new SearchFilterState();
    state.setCurrentStep(FilterStep.PRICE);
    state.setDealType(DealType.RENT);

    // When
    String text = factory.getStepText(state);

    // Then
    assertThat(text).isEqualTo("💰 Диапазон цены (BYN/мес):");
  }

  // -------------------------------------------------------------------------
  // DONE step — issue #479
  // -------------------------------------------------------------------------

  @Test
  void should_show_search_label_when_done_step_is_not_editing_a_subscription() {
    // Given
    var state = new SearchFilterState();
    state.setCurrentStep(FilterStep.DONE);

    // When
    List<String> labels = extractButtonLabels(factory.buildForStep(state));

    // Then
    assertThat(labels).contains("🔍 Найти");
  }

  @Test
  void should_show_save_label_when_done_step_is_editing_a_subscription() {
    // Given
    var state = new SearchFilterState();
    state.setCurrentStep(FilterStep.DONE);
    state.setEditingSubscriptionId(5L);

    // When
    List<String> labels = extractButtonLabels(factory.buildForStep(state));

    // Then
    assertThat(labels).contains("💾 Сохранить изменения");
    assertThat(labels).doesNotContain("🔍 Найти");
  }

  // -------------------------------------------------------------------------
  // CITY step (#503)
  // -------------------------------------------------------------------------

  @Test
  void should_show_city_prompt_at_city_step() {
    // Given
    var state = new SearchFilterState();
    state.setCurrentStep(FilterStep.CITY);

    // When
    String text = factory.getStepText(state);

    // Then
    assertThat(text).isEqualTo("🏙 Выберите город:");
  }

  @Test
  void should_show_city_buttons_from_city_service_at_city_step() {
    // Given
    when(cityService.findAll()).thenReturn(List.of(buildCity(1L, "Минск"), buildCity(2L, "Брест")));
    var state = new SearchFilterState();
    state.setCurrentStep(FilterStep.CITY);

    // When
    List<String> labels = extractButtonLabels(factory.buildForStep(state));

    // Then
    assertThat(labels).contains("Минск", "Брест", "Любой");
  }

  @Test
  void should_show_any_city_label_in_summary_when_city_not_selected() {
    // Given
    var state = new SearchFilterState();
    state.setCurrentStep(FilterStep.DONE);

    // When
    String text = factory.getStepText(state);

    // Then
    assertThat(text).contains("Город: Любой");
  }

  @Test
  void should_show_city_name_in_summary_when_city_selected() {
    // Given
    when(cityService.findById(1L)).thenReturn(Optional.of(buildCity(1L, "Минск")));
    var state = new SearchFilterState();
    state.setCurrentStep(FilterStep.DONE);
    state.setCityId(1L);

    // When
    String text = factory.getStepText(state);

    // Then
    assertThat(text).contains("Город: Минск");
  }

  // -------------------------------------------------------------------------
  // Helper
  // -------------------------------------------------------------------------

  private List<String> extractButtonLabels(InlineKeyboardMarkup keyboard) {
    return keyboard.getKeyboard().stream()
        .flatMap(row -> row.stream())
        .map(InlineKeyboardButton::getText)
        .toList();
  }
}
