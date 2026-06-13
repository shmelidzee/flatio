package com.flatio.telegram.keyboard;

import com.flatio.domain.city.City;
import com.flatio.domain.listing.DealType;
import com.flatio.telegram.state.FilterStep;
import com.flatio.telegram.state.SearchFilterState;
import com.flatio.telegram.state.SearchFilterWizard;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

/**
 * Builds inline keyboards and prompt texts for each step of the search filter wizard.
 */
@Component
public class FilterKeyboardFactory {

  private static final String P = SearchFilterWizard.CALLBACK_PREFIX;

  /** Maximum number of city buttons shown per keyboard without filtering. */
  private static final int MAX_CITY_BUTTONS = 8;

  /**
   * Returns the inline keyboard markup for the current wizard step.
   *
   * @param state current filter state, never null
   * @return keyboard markup appropriate for the current step, never null
   */
  public InlineKeyboardMarkup buildForStep(SearchFilterState state) {
    return switch (state.getCurrentStep()) {
      case DEAL_TYPE -> buildDealTypeKeyboard();
      case PROPERTY_TYPE -> buildPropertyTypeKeyboard();
      case ROOMS -> buildRoomsKeyboard();
      case PRICE -> buildPriceKeyboard(state.getDealType());
      case OWNER_ONLY -> buildOwnerOnlyKeyboard();
      case CITY -> buildCityKeyboard(List.of());
      case KEYWORD -> buildKeywordKeyboard();
      case DONE -> buildDoneKeyboard();
    };
  }

  /**
   * Builds the city selection keyboard from the given list of cities.
   *
   * <p>Each city is rendered as a button with callback {@code FILTER:CITY:<id>}.
   * A «Пропустить» button and a «Определить по геолокации» button are always included.
   * When the list is empty only those two action buttons and the navigation row are shown.
   * The list is capped at {@value #MAX_CITY_BUTTONS} entries to keep the keyboard compact.
   *
   * @param cities list of cities to display; may be empty, never null
   * @return inline keyboard markup for the CITY step, never null
   */
  public InlineKeyboardMarkup buildCityKeyboard(List<City> cities) {
    var builder = InlineKeyboardMarkup.builder();
    var geoBtn = btn("📍 Определить по геолокации", P + ":CITY:GEO");
    var skipBtn = btn("Пропустить", P + ":CITY:ANY");
    builder.keyboardRow(new InlineKeyboardRow(geoBtn));
    int limit = Math.min(cities.size(), MAX_CITY_BUTTONS);
    for (int i = 0; i < limit; i++) {
      var city = cities.get(i);
      builder.keyboardRow(new InlineKeyboardRow(btn(city.getNameRu(), P + ":CITY:" + city.getId())));
    }
    builder.keyboardRow(new InlineKeyboardRow(skipBtn));
    builder.keyboardRow(navRow());
    return builder.build();
  }

  /**
   * Returns the prompt text displayed above the keyboard for the current wizard step.
   *
   * @param state current filter state, never null
   * @return prompt text, never null
   */
  public String getStepText(SearchFilterState state) {
    return switch (state.getCurrentStep()) {
      case DEAL_TYPE -> "🏠 Выберите тип сделки:";
      case PROPERTY_TYPE -> "🏢 Тип недвижимости:";
      case ROOMS -> "🛏 Количество комнат:";
      case PRICE -> state.getDealType() == DealType.SELL
          ? "💰 Диапазон цены (BYN):"
          : "💰 Диапазон цены (BYN/мес):";
      case OWNER_ONLY -> "👤 Тип продавца:";
      case CITY -> "🏙 Выберите город или введите часть названия для поиска:";
      case KEYWORD -> "🔍 Введите ключевые слова для поиска\nили нажмите «Пропустить»:";
      case DONE -> buildSummaryText(state);
    };
  }

  private InlineKeyboardMarkup buildDealTypeKeyboard() {
    var rent = btn("Аренда", P + ":DEAL_TYPE:RENT");
    var sell = btn("Продажа", P + ":DEAL_TYPE:SELL");
    var any = btn("Любой", P + ":DEAL_TYPE:ANY");
    return InlineKeyboardMarkup.builder()
        .keyboardRow(new InlineKeyboardRow(rent, sell))
        .keyboardRow(new InlineKeyboardRow(any))
        .keyboardRow(resetRow())
        .build();
  }

  private InlineKeyboardMarkup buildPropertyTypeKeyboard() {
    var apartment = btn("Квартира", P + ":PROPERTY_TYPE:APARTMENT");
    var house = btn("Дом", P + ":PROPERTY_TYPE:HOUSE");
    var room = btn("Комната", P + ":PROPERTY_TYPE:ROOM");
    var any = btn("Любой", P + ":PROPERTY_TYPE:ANY");
    return InlineKeyboardMarkup.builder()
        .keyboardRow(new InlineKeyboardRow(apartment, house, room))
        .keyboardRow(new InlineKeyboardRow(any))
        .keyboardRow(navRow())
        .build();
  }

  private InlineKeyboardMarkup buildRoomsKeyboard() {
    var r1 = btn("1", P + ":ROOMS:1");
    var r2 = btn("2", P + ":ROOMS:2");
    var r3 = btn("3", P + ":ROOMS:3");
    var r4 = btn("4+", P + ":ROOMS:4_PLUS");
    var any = btn("Любое", P + ":ROOMS:ANY");
    return InlineKeyboardMarkup.builder()
        .keyboardRow(new InlineKeyboardRow(r1, r2, r3, r4))
        .keyboardRow(new InlineKeyboardRow(any))
        .keyboardRow(navRow())
        .build();
  }

  private InlineKeyboardMarkup buildPriceKeyboard(DealType dealType) {
    boolean isSale = dealType == DealType.SELL;
    var low = isSale
        ? btn("до 100 000", P + ":PRICE:LOW")
        : btn("до 1 000", P + ":PRICE:LOW");
    var med = isSale
        ? btn("100 000–200 000", P + ":PRICE:MEDIUM")
        : btn("1 000–2 000", P + ":PRICE:MEDIUM");
    var high = isSale
        ? btn("200 000–400 000", P + ":PRICE:HIGH")
        : btn("2 000–4 000", P + ":PRICE:HIGH");
    var premium = isSale
        ? btn("400 000+", P + ":PRICE:PREMIUM")
        : btn("4 000+", P + ":PRICE:PREMIUM");
    var any = btn("Любая", P + ":PRICE:ANY");
    return InlineKeyboardMarkup.builder()
        .keyboardRow(new InlineKeyboardRow(low, med))
        .keyboardRow(new InlineKeyboardRow(high, premium))
        .keyboardRow(new InlineKeyboardRow(any))
        .keyboardRow(navRow())
        .build();
  }

  private InlineKeyboardMarkup buildOwnerOnlyKeyboard() {
    var ownerOnly = btn("Только собственник", P + ":OWNER_ONLY:true");
    var any = btn("Не важно", P + ":OWNER_ONLY:ANY");
    return InlineKeyboardMarkup.builder()
        .keyboardRow(new InlineKeyboardRow(ownerOnly))
        .keyboardRow(new InlineKeyboardRow(any))
        .keyboardRow(navRow())
        .build();
  }

  private InlineKeyboardMarkup buildKeywordKeyboard() {
    var skip = btn("Пропустить", P + ":KEYWORD:ANY");
    return InlineKeyboardMarkup.builder()
        .keyboardRow(new InlineKeyboardRow(skip))
        .keyboardRow(navRow())
        .build();
  }

  private InlineKeyboardMarkup buildDoneKeyboard() {
    var search = btn("🔍 Найти", P + ":SEARCH");
    var reset = btn("🔄 Изменить фильтр", P + ":RESET");
    return InlineKeyboardMarkup.builder()
        .keyboardRow(new InlineKeyboardRow(search))
        .keyboardRow(new InlineKeyboardRow(reset))
        .build();
  }

  private InlineKeyboardRow navRow() {
    return new InlineKeyboardRow(
        btn("← Назад", P + ":BACK"),
        btn("🔄 Сбросить", P + ":RESET")
    );
  }

  private InlineKeyboardRow resetRow() {
    return new InlineKeyboardRow(btn("🔄 Сбросить", P + ":RESET"));
  }

  private InlineKeyboardButton btn(String text, String callbackData) {
    return InlineKeyboardButton.builder().text(text).callbackData(callbackData).build();
  }

  private String buildSummaryText(SearchFilterState state) {
    var sb = new StringBuilder("✅ Фильтр настроен:\n")
        .append("Сделка: ").append(dealTypeLabel(state.getDealType())).append("\n")
        .append("Тип: ").append(propertyTypeLabel(state.getPropertyType())).append("\n")
        .append("Комнат: ").append(roomsLabel(state.getRooms())).append("\n")
        .append("Цена: ").append(priceLabel(state.getPriceMin(), state.getPriceMax())).append("\n")
        .append("Продавец: ").append(ownerOnlyLabel(state.getOwnerOnly())).append("\n")
        .append("Город: ").append(cityIdLabel(state.getCityId()));
    if (state.getQuery() != null && !state.getQuery().isBlank()) {
      sb.append("\nКлючевые слова: «").append(escapeHtml(state.getQuery())).append("»");
    }
    return sb.toString();
  }

  private String cityIdLabel(Long cityId) {
    if (cityId == null) return "Любой";
    return "ID " + cityId;
  }

  private String escapeHtml(String text) {
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }

  private String dealTypeLabel(DealType dealType) {
    if (dealType == null) return "Любой";
    return switch (dealType) {
      case RENT -> "Аренда";
      case SELL -> "Продажа";
      case RENT_DAILY -> "Посуточно";
    };
  }

  private String propertyTypeLabel(String propertyType) {
    if (propertyType == null) return "Любой";
    return switch (propertyType) {
      case "APARTMENT" -> "Квартира";
      case "HOUSE" -> "Дом";
      case "ROOM" -> "Комната";
      default -> propertyType;
    };
  }

  private String roomsLabel(Integer rooms) {
    if (rooms == null) return "Любое";
    return rooms >= 4 ? "4+" : rooms.toString();
  }

  private String priceLabel(BigDecimal priceMin, BigDecimal priceMax) {
    if (priceMin == null && priceMax == null) return "Любая";
    if (priceMin == null) return "до " + priceMax.toPlainString() + " BYN";
    if (priceMax == null) return "от " + priceMin.toPlainString() + " BYN";
    return priceMin.toPlainString() + "–" + priceMax.toPlainString() + " BYN";
  }

  private String ownerOnlyLabel(Boolean ownerOnly) {
    if (ownerOnly == null) return "Не важно";
    return ownerOnly ? "Только собственник" : "Не важно";
  }
}
