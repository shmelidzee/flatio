package com.flatio.telegram.keyboard;

import com.flatio.domain.listing.DealType;
import com.flatio.config.SellPriceFilterProperties;
import com.flatio.telegram.state.FilterStep;
import com.flatio.telegram.state.SearchFilterState;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import static org.assertj.core.api.Assertions.assertThat;

class FilterKeyboardFactoryTest {

  private static final SellPriceFilterProperties DEFAULT_PROPS = new SellPriceFilterProperties(
      BigDecimal.valueOf(100_000),
      BigDecimal.valueOf(200_000),
      BigDecimal.valueOf(400_000)
  );

  private FilterKeyboardFactory factory;

  @BeforeEach
  void setUp() {
    factory = new FilterKeyboardFactory(DEFAULT_PROPS);
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
    var customFactory = new FilterKeyboardFactory(customProps);
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

    // Then — issue #526: also mentions the custom-range free-text option
    assertThat(text).startsWith("💰 Диапазон цены (BYN):");
  }

  @Test
  void should_show_rent_price_prompt_when_deal_type_is_rent() {
    // Given
    var state = new SearchFilterState();
    state.setCurrentStep(FilterStep.PRICE);
    state.setDealType(DealType.RENT);

    // When
    String text = factory.getStepText(state);

    // Then — issue #526: also mentions the custom-range free-text option
    assertThat(text).startsWith("💰 Диапазон цены (BYN/мес):");
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
  // Current-value highlighting when editing a subscription (issue #523)
  // -------------------------------------------------------------------------

  @Test
  void should_mark_current_deal_type_when_editing() {
    // Given
    var state = new SearchFilterState();
    state.setCurrentStep(FilterStep.DEAL_TYPE);
    state.setDealType(DealType.RENT);
    state.setEditingSubscriptionId(5L);

    // When
    List<String> labels = extractButtonLabels(factory.buildForStep(state));

    // Then
    assertThat(labels).contains("✅ Аренда");
    assertThat(labels).doesNotContain("Аренда", "✅ Продажа", "✅ Любой");
  }

  @Test
  void should_not_mark_deal_type_when_not_editing() {
    // Given — plain new-search wizard must look exactly as before
    var state = new SearchFilterState();
    state.setCurrentStep(FilterStep.DEAL_TYPE);
    state.setDealType(DealType.RENT);

    // When
    List<String> labels = extractButtonLabels(factory.buildForStep(state));

    // Then
    assertThat(labels).contains("Аренда").doesNotContain("✅ Аренда");
  }

  @Test
  void should_mark_current_property_type_when_editing() {
    // Given
    var state = new SearchFilterState();
    state.setCurrentStep(FilterStep.PROPERTY_TYPE);
    state.setPropertyType("HOUSE");
    state.setEditingSubscriptionId(5L);

    // When
    List<String> labels = extractButtonLabels(factory.buildForStep(state));

    // Then
    assertThat(labels).contains("✅ Дом").doesNotContain("Дом");
  }

  @Test
  void should_mark_any_property_type_when_editing_and_no_property_type_set() {
    // Given
    var state = new SearchFilterState();
    state.setCurrentStep(FilterStep.PROPERTY_TYPE);
    state.setEditingSubscriptionId(5L);

    // When
    List<String> labels = extractButtonLabels(factory.buildForStep(state));

    // Then
    assertThat(labels).contains("✅ Любой");
  }

  @Test
  void should_mark_current_rooms_when_editing() {
    // Given
    var state = new SearchFilterState();
    state.setCurrentStep(FilterStep.ROOMS);
    state.setRooms(2);
    state.setEditingSubscriptionId(5L);

    // When
    List<String> labels = extractButtonLabels(factory.buildForStep(state));

    // Then
    assertThat(labels).contains("✅ 2").doesNotContain("✅ 1", "✅ 3", "✅ 4+");
  }

  @Test
  void should_mark_four_plus_rooms_when_editing_with_rooms_above_four() {
    // Given
    var state = new SearchFilterState();
    state.setCurrentStep(FilterStep.ROOMS);
    state.setRooms(6);
    state.setEditingSubscriptionId(5L);

    // When
    List<String> labels = extractButtonLabels(factory.buildForStep(state));

    // Then
    assertThat(labels).contains("✅ 4+");
  }

  @Test
  void should_mark_matching_price_preset_when_editing() {
    // Given — matches the RENT "1 000–2 000" preset exactly
    var state = new SearchFilterState();
    state.setCurrentStep(FilterStep.PRICE);
    state.setDealType(DealType.RENT);
    state.setPriceMin(BigDecimal.valueOf(1_000));
    state.setPriceMax(BigDecimal.valueOf(2_000));
    state.setEditingSubscriptionId(5L);

    // When
    List<String> labels = extractButtonLabels(factory.buildForStep(state));

    // Then
    assertThat(labels).contains("✅ 1 000–2 000");
    assertThat(labels).doesNotContain("✅ до 1 000", "✅ 2 000–4 000", "✅ 4 000+", "✅ Любая");
  }

  @Test
  void should_not_mark_any_preset_when_custom_range_and_editing() {
    // Given — a custom range entered via issue #526 that matches no preset boundary
    var state = new SearchFilterState();
    state.setCurrentStep(FilterStep.PRICE);
    state.setDealType(DealType.RENT);
    state.setPriceMin(BigDecimal.valueOf(1_200));
    state.setPriceMax(BigDecimal.valueOf(1_800));
    state.setEditingSubscriptionId(5L);

    // When
    List<String> labels = extractButtonLabels(factory.buildForStep(state));

    // Then — none of the preset buttons are highlighted
    assertThat(labels).noneMatch(label -> label.startsWith("✅"));
  }

  @Test
  void should_mark_current_owner_only_when_editing() {
    // Given
    var state = new SearchFilterState();
    state.setCurrentStep(FilterStep.OWNER_ONLY);
    state.setOwnerOnly(true);
    state.setEditingSubscriptionId(5L);

    // When
    List<String> labels = extractButtonLabels(factory.buildForStep(state));

    // Then
    assertThat(labels).contains("✅ Только собственник").doesNotContain("✅ Не важно");
  }

  // -------------------------------------------------------------------------
  // KEYWORD step — keep vs. clear when editing (issue #523)
  // -------------------------------------------------------------------------

  @Test
  void should_show_keep_and_clear_buttons_when_editing_with_existing_keyword() {
    // Given
    var state = new SearchFilterState();
    state.setCurrentStep(FilterStep.KEYWORD);
    state.setQuery("Минск");
    state.setEditingSubscriptionId(5L);

    // When
    List<String> labels = extractButtonLabels(factory.buildForStep(state));

    // Then
    assertThat(labels).contains("Пропустить (оставить как есть)", "🗑 Очистить");
  }

  @Test
  void should_show_single_skip_button_when_editing_without_existing_keyword() {
    // Given
    var state = new SearchFilterState();
    state.setCurrentStep(FilterStep.KEYWORD);
    state.setEditingSubscriptionId(5L);

    // When
    List<String> labels = extractButtonLabels(factory.buildForStep(state));

    // Then — unchanged single-button layout when there is nothing to keep
    assertThat(labels).contains("Пропустить");
    assertThat(labels).doesNotContain("🗑 Очистить", "Пропустить (оставить как есть)");
  }

  @Test
  void should_show_single_skip_button_when_not_editing_even_with_query_set() {
    // Given — plain new-search wizard: never in "keep vs clear" mode
    var state = new SearchFilterState();
    state.setCurrentStep(FilterStep.KEYWORD);
    state.setQuery("Минск");

    // When
    List<String> labels = extractButtonLabels(factory.buildForStep(state));

    // Then
    assertThat(labels).contains("Пропустить");
    assertThat(labels).doesNotContain("🗑 Очистить", "Пропустить (оставить как есть)");
  }

  @Test
  void should_include_current_keyword_in_step_text_when_editing_with_existing_keyword() {
    // Given
    var state = new SearchFilterState();
    state.setCurrentStep(FilterStep.KEYWORD);
    state.setQuery("Минск");
    state.setEditingSubscriptionId(5L);

    // When
    String text = factory.getStepText(state);

    // Then
    assertThat(text).contains("Текущее значение: «Минск»");
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
