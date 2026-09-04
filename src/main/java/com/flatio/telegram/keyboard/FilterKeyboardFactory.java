package com.flatio.telegram.keyboard;

import com.flatio.common.util.TelegramHtmlEscaper;
import com.flatio.domain.listing.DealType;
import com.flatio.config.SellPriceFilterProperties;
import com.flatio.telegram.state.FilterStep;
import com.flatio.telegram.state.SearchFilterState;
import com.flatio.telegram.state.SearchFilterWizard;
import java.math.BigDecimal;
import java.math.RoundingMode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

/**
 * Builds inline keyboards and prompt texts for each step of the search filter wizard.
 */
@Component
@RequiredArgsConstructor
public class FilterKeyboardFactory {

  private static final String P = SearchFilterWizard.CALLBACK_PREFIX;

  private final SellPriceFilterProperties sellPriceProps;

  /**
   * Returns the inline keyboard markup for the current wizard step.
   *
   * @param state current filter state, never null
   * @return keyboard markup appropriate for the current step, never null
   */
  public InlineKeyboardMarkup buildForStep(SearchFilterState state) {
    return switch (state.getCurrentStep()) {
      case DEAL_TYPE -> buildDealTypeKeyboard(state);
      case PROPERTY_TYPE -> buildPropertyTypeKeyboard(state);
      case ROOMS -> buildRoomsKeyboard(state);
      case PRICE -> buildPriceKeyboard(state);
      case OWNER_ONLY -> buildOwnerOnlyKeyboard(state);
      case KEYWORD -> buildKeywordKeyboard(state);
      case DONE -> buildDoneKeyboard(state);
    };
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
      case PRICE -> (state.getDealType() == DealType.SELL
          ? "💰 Диапазон цены (BYN):"
          : "💰 Диапазон цены (BYN/мес):")
          + "\nМожно выбрать вариант ниже или ввести свой диапазон текстом, например «1200-1800».";
      case OWNER_ONLY -> "👤 Тип продавца:";
      case KEYWORD -> buildKeywordStepText(state);
      case DONE -> buildSummaryText(state);
    };
  }

  private boolean isEditing(SearchFilterState state) {
    return state.getEditingSubscriptionId() != null;
  }

  private String mark(String label, boolean isCurrent) {
    return isCurrent ? "✅ " + label : label;
  }

  private InlineKeyboardMarkup buildDealTypeKeyboard(SearchFilterState state) {
    boolean editing = isEditing(state);
    DealType current = state.getDealType();
    var rent = btn(mark("Аренда", editing && current == DealType.RENT), P + ":DEAL_TYPE:RENT");
    var sell = btn(mark("Продажа", editing && current == DealType.SELL), P + ":DEAL_TYPE:SELL");
    var any = btn(mark("Любой", editing && current == null), P + ":DEAL_TYPE:ANY");
    return InlineKeyboardMarkup.builder()
        .keyboardRow(new InlineKeyboardRow(rent, sell))
        .keyboardRow(new InlineKeyboardRow(any))
        .keyboardRow(resetRow())
        .build();
  }

  private InlineKeyboardMarkup buildPropertyTypeKeyboard(SearchFilterState state) {
    boolean editing = isEditing(state);
    String current = state.getPropertyType();
    var apartment = btn(mark("Квартира", editing && "APARTMENT".equals(current)), P + ":PROPERTY_TYPE:APARTMENT");
    var house = btn(mark("Дом", editing && "HOUSE".equals(current)), P + ":PROPERTY_TYPE:HOUSE");
    var room = btn(mark("Комната", editing && "ROOM".equals(current)), P + ":PROPERTY_TYPE:ROOM");
    var any = btn(mark("Любой", editing && current == null), P + ":PROPERTY_TYPE:ANY");
    return InlineKeyboardMarkup.builder()
        .keyboardRow(new InlineKeyboardRow(apartment, house, room))
        .keyboardRow(new InlineKeyboardRow(any))
        .keyboardRow(navRow())
        .build();
  }

  private InlineKeyboardMarkup buildRoomsKeyboard(SearchFilterState state) {
    boolean editing = isEditing(state);
    Integer current = state.getRooms();
    var r1 = btn(mark("1", editing && Integer.valueOf(1).equals(current)), P + ":ROOMS:1");
    var r2 = btn(mark("2", editing && Integer.valueOf(2).equals(current)), P + ":ROOMS:2");
    var r3 = btn(mark("3", editing && Integer.valueOf(3).equals(current)), P + ":ROOMS:3");
    var r4 = btn(mark("4+", editing && current != null && current >= 4), P + ":ROOMS:4_PLUS");
    var any = btn(mark("Любое", editing && current == null), P + ":ROOMS:ANY");
    return InlineKeyboardMarkup.builder()
        .keyboardRow(new InlineKeyboardRow(r1, r2, r3, r4))
        .keyboardRow(new InlineKeyboardRow(any))
        .keyboardRow(navRow())
        .build();
  }

  private InlineKeyboardMarkup buildPriceKeyboard(SearchFilterState state) {
    DealType dealType = state.getDealType();
    boolean isSale = dealType == DealType.SELL;
    boolean editing = isEditing(state);
    String preset = editing ? matchingPricePreset(isSale, state.getPriceMin(), state.getPriceMax()) : null;
    var low = isSale
        ? btn(mark("до " + formatPrice(sellPriceProps.lowMax()), "LOW".equals(preset)), P + ":PRICE:LOW")
        : btn(mark("до 1 000", "LOW".equals(preset)), P + ":PRICE:LOW");
    var med = isSale
        ? btn(mark(formatPrice(sellPriceProps.lowMax()) + "–" + formatPrice(sellPriceProps.mediumMax()), "MEDIUM".equals(preset)), P + ":PRICE:MEDIUM")
        : btn(mark("1 000–2 000", "MEDIUM".equals(preset)), P + ":PRICE:MEDIUM");
    var high = isSale
        ? btn(mark(formatPrice(sellPriceProps.mediumMax()) + "–" + formatPrice(sellPriceProps.highMax()), "HIGH".equals(preset)), P + ":PRICE:HIGH")
        : btn(mark("2 000–4 000", "HIGH".equals(preset)), P + ":PRICE:HIGH");
    var premium = isSale
        ? btn(mark(formatPrice(sellPriceProps.highMax()) + "+", "PREMIUM".equals(preset)), P + ":PRICE:PREMIUM")
        : btn(mark("4 000+", "PREMIUM".equals(preset)), P + ":PRICE:PREMIUM");
    var any = btn(mark("Любая", "ANY".equals(preset)), P + ":PRICE:ANY");
    return InlineKeyboardMarkup.builder()
        .keyboardRow(new InlineKeyboardRow(low, med))
        .keyboardRow(new InlineKeyboardRow(high, premium))
        .keyboardRow(new InlineKeyboardRow(any))
        .keyboardRow(navRow())
        .build();
  }

  /**
   * Determines which price preset bucket, if any, the current {@code (priceMin, priceMax)} pair
   * matches exactly — used to highlight the active preset when editing a subscription (issue
   * #523). Returns null for a range that does not match a preset boundary exactly (e.g. a custom
   * range entered via issue #526), in which case no preset button is highlighted.
   *
   * @param isSale   true for the SELL price ladder, false for RENT
   * @param priceMin current minimum, or null
   * @param priceMax current maximum, or null
   * @return preset key ("LOW"/"MEDIUM"/"HIGH"/"PREMIUM"/"ANY"), or null if none matches
   */
  private String matchingPricePreset(boolean isSale, BigDecimal priceMin, BigDecimal priceMax) {
    if (priceMin == null && priceMax == null) {
      return "ANY";
    }
    BigDecimal lowMax = isSale ? sellPriceProps.lowMax() : BigDecimal.valueOf(1_000);
    BigDecimal mediumMax = isSale ? sellPriceProps.mediumMax() : BigDecimal.valueOf(2_000);
    BigDecimal highMax = isSale ? sellPriceProps.highMax() : BigDecimal.valueOf(4_000);
    if (priceMin == null && sameValue(priceMax, lowMax)) {
      return "LOW";
    }
    if (sameValue(priceMin, lowMax) && sameValue(priceMax, mediumMax)) {
      return "MEDIUM";
    }
    if (sameValue(priceMin, mediumMax) && sameValue(priceMax, highMax)) {
      return "HIGH";
    }
    if (sameValue(priceMin, highMax) && priceMax == null) {
      return "PREMIUM";
    }
    return null;
  }

  private boolean sameValue(BigDecimal a, BigDecimal b) {
    return a != null && b != null && a.compareTo(b) == 0;
  }

  private static String formatPrice(BigDecimal value) {
    long v = value.setScale(0, RoundingMode.HALF_UP).longValueExact();
    if (v >= 1_000_000) {
      return String.format("%d %03d %03d", v / 1_000_000, (v % 1_000_000) / 1_000, v % 1_000);
    }
    if (v >= 1_000) {
      return String.format("%d %03d", v / 1_000, v % 1_000);
    }
    return String.valueOf(v);
  }

  private InlineKeyboardMarkup buildOwnerOnlyKeyboard(SearchFilterState state) {
    boolean editing = isEditing(state);
    Boolean current = state.getOwnerOnly();
    var ownerOnly = btn(mark("Только собственник", editing && Boolean.TRUE.equals(current)), P + ":OWNER_ONLY:true");
    var any = btn(mark("Не важно", editing && current == null), P + ":OWNER_ONLY:ANY");
    return InlineKeyboardMarkup.builder()
        .keyboardRow(new InlineKeyboardRow(ownerOnly))
        .keyboardRow(new InlineKeyboardRow(any))
        .keyboardRow(navRow())
        .build();
  }

  /**
   * Builds the KEYWORD-step keyboard. When editing a subscription that already has a keyword
   * (issue #523), "Пропустить" leaves it untouched ({@code KEYWORD:KEEP}) and a separate
   * "🗑 Очистить" removes it explicitly ({@code KEYWORD:ANY}, the same "no value" callback every
   * other step's "skip" already uses) — previously the single "Пропустить" button always cleared
   * the keyword, silently discarding it even when the user only meant to leave it as-is.
   *
   * @param state current filter state, never null
   * @return KEYWORD-step keyboard markup, never null
   */
  private InlineKeyboardMarkup buildKeywordKeyboard(SearchFilterState state) {
    var builder = InlineKeyboardMarkup.builder();
    if (hasEditableExistingQuery(state)) {
      builder.keyboardRow(new InlineKeyboardRow(btn("Пропустить (оставить как есть)", P + ":KEYWORD:KEEP")));
      builder.keyboardRow(new InlineKeyboardRow(btn("🗑 Очистить", P + ":KEYWORD:ANY")));
    } else {
      builder.keyboardRow(new InlineKeyboardRow(btn("Пропустить", P + ":KEYWORD:ANY")));
    }
    builder.keyboardRow(navRow());
    return builder.build();
  }

  private boolean hasEditableExistingQuery(SearchFilterState state) {
    return isEditing(state) && state.getQuery() != null && !state.getQuery().isBlank();
  }

  private String buildKeywordStepText(SearchFilterState state) {
    if (hasEditableExistingQuery(state)) {
      return "🔍 Ключевые слова (можно указать город).\nТекущее значение: «"
          + TelegramHtmlEscaper.escapeHtml(state.getQuery())
          + "». Отправьте новый текст, нажмите «Пропустить» чтобы оставить как есть, "
          + "или «Очистить» чтобы убрать:";
    }
    return "🔍 Введите ключевые слова для поиска (можно указать город)\nили нажмите «Пропустить»:";
  }

  /**
   * Builds the DONE-step keyboard. The primary button reads "🔍 Найти" for a plain search, or
   * "💾 Сохранить изменения" when the wizard is editing an existing subscription's criteria
   * (issue #479) — both use the same {@code FILTER:SEARCH} callback, which the router dispatches
   * to search execution or subscription update based on {@link SearchFilterState#getEditingSubscriptionId()}.
   *
   * @param state current filter state, never null
   * @return DONE-step keyboard markup, never null
   */
  private InlineKeyboardMarkup buildDoneKeyboard(SearchFilterState state) {
    String primaryLabel = state.getEditingSubscriptionId() != null ? "💾 Сохранить изменения" : "🔍 Найти";
    var primary = btn(primaryLabel, P + ":SEARCH");
    var reset = btn("🔄 Изменить фильтр", P + ":RESET");
    return InlineKeyboardMarkup.builder()
        .keyboardRow(new InlineKeyboardRow(primary))
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
        .append("Продавец: ").append(ownerOnlyLabel(state.getOwnerOnly()));
    if (state.getQuery() != null && !state.getQuery().isBlank()) {
      sb.append("\nКлючевые слова: «").append(TelegramHtmlEscaper.escapeHtml(state.getQuery())).append("»");
    }
    return sb.toString();
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
