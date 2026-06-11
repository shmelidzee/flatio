package com.flatio.telegram.state;

import com.flatio.domain.listing.DealType;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * In-memory wizard that guides users through search filter selection.
 *
 * <p>State is stored in a {@link ConcurrentHashMap} keyed by Telegram user ID.
 * MVP — survives within a single JVM instance only (no Redis/DB persistence).
 *
 * <p><b>MVP limitation:</b> state is never evicted automatically. Each active user
 * occupies a small entry for the lifetime of the JVM. Replace with Caffeine Cache
 * ({@code expireAfterWrite(30m)}) before production-scale deployment.
 */
@Component
@Slf4j
public class SearchFilterWizard {

  /** Callback prefix used by all filter-related keyboard buttons. */
  public static final String CALLBACK_PREFIX = "FILTER";

  /** Callback value meaning "no filter applied" for a given step. */
  public static final String VALUE_ANY = "ANY";

  private static final Set<String> ALLOWED_PROPERTY_TYPES = Set.of("APARTMENT", "HOUSE", "ROOM");

  static final BigDecimal PRICE_LOW_MAX = BigDecimal.valueOf(1_000);
  static final BigDecimal PRICE_MED_MIN = BigDecimal.valueOf(1_000);
  static final BigDecimal PRICE_MED_MAX = BigDecimal.valueOf(2_000);
  static final BigDecimal PRICE_HIGH_MIN = BigDecimal.valueOf(2_000);
  static final BigDecimal PRICE_HIGH_MAX = BigDecimal.valueOf(4_000);
  static final BigDecimal PRICE_PREMIUM_MIN = BigDecimal.valueOf(4_000);

  private final Map<Long, SearchFilterState> states = new ConcurrentHashMap<>();

  /**
   * Starts or resets the wizard for the given user.
   *
   * @param telegramId Telegram user identifier, never null
   * @return fresh wizard state positioned at the first step
   */
  public SearchFilterState start(Long telegramId) {
    var state = new SearchFilterState();
    states.put(telegramId, state);
    log.debug("Filter wizard started: telegramId={}", telegramId);
    return state;
  }

  /**
   * Returns the current wizard state for the user, if any.
   *
   * @param telegramId Telegram user identifier, never null
   * @return current state, or empty if the wizard has not been started
   */
  public Optional<SearchFilterState> getState(Long telegramId) {
    return Optional.ofNullable(states.get(telegramId));
  }

  /**
   * Applies the selected value to the current wizard step and advances to the next step.
   *
   * <p>When the user selects property type {@code ROOM}, the ROOMS step is skipped because
   * a room is an indivisible unit — asking for room count is not applicable.
   *
   * @param telegramId Telegram user identifier, never null
   * @param step       step that was answered, never null
   * @param value      selected option value, never null
   * @return updated state after the selection is applied
   */
  public SearchFilterState applySelection(Long telegramId, FilterStep step, String value) {
    var state = states.computeIfAbsent(telegramId, id -> new SearchFilterState());
    switch (step) {
      case DEAL_TYPE -> {
        state.setDealType(VALUE_ANY.equals(value) ? null : parseDealType(value));
        state.setCurrentStep(FilterStep.PROPERTY_TYPE);
      }
      case PROPERTY_TYPE -> {
        String propertyType = VALUE_ANY.equals(value) ? null : parsePropertyType(value);
        state.setPropertyType(propertyType);
        // ROOM is indivisible — skip the ROOMS step
        state.setCurrentStep("ROOM".equals(propertyType) ? FilterStep.PRICE : FilterStep.ROOMS);
      }
      case ROOMS -> {
        state.setRooms(VALUE_ANY.equals(value) ? null : parseRooms(value));
        state.setCurrentStep(FilterStep.PRICE);
      }
      case PRICE -> {
        applyPriceRange(state, value);
        state.setCurrentStep(FilterStep.OWNER_ONLY);
      }
      case OWNER_ONLY -> {
        state.setOwnerOnly(VALUE_ANY.equals(value) ? null : Boolean.parseBoolean(value));
        state.setCurrentStep(FilterStep.KEYWORD);
      }
      case KEYWORD -> {
        state.setQuery(VALUE_ANY.equals(value) ? null : value);
        state.setCurrentStep(FilterStep.DONE);
      }
      default -> log.warn("Unexpected step in applySelection: step={}", step);
    }
    log.debug("Filter step applied: telegramId={}, step={}, value={}", telegramId, step, value);
    return state;
  }

  /**
   * Moves the wizard one step back and clears the previously selected value.
   *
   * <p>When stepping back from PRICE with property type ROOM, the wizard jumps to
   * PROPERTY_TYPE (skipping ROOMS, consistent with the forward direction).
   *
   * @param telegramId Telegram user identifier, never null
   * @return updated state after stepping back; restarts the wizard if already at the first step
   */
  public SearchFilterState stepBack(Long telegramId) {
    var state = states.computeIfAbsent(telegramId, id -> new SearchFilterState());
    switch (state.getCurrentStep()) {
      case DEAL_TYPE -> { return start(telegramId); }
      case PROPERTY_TYPE -> { state.setDealType(null); state.setCurrentStep(FilterStep.DEAL_TYPE); }
      case ROOMS -> { state.setPropertyType(null); state.setCurrentStep(FilterStep.PROPERTY_TYPE); }
      case PRICE -> {
        state.setRooms(null);
        // ROOM type skips ROOMS step in both directions
        boolean wasRoom = "ROOM".equals(state.getPropertyType());
        state.setCurrentStep(wasRoom ? FilterStep.PROPERTY_TYPE : FilterStep.ROOMS);
      }
      case OWNER_ONLY -> {
        state.setPriceMin(null);
        state.setPriceMax(null);
        state.setCurrentStep(FilterStep.PRICE);
      }
      case KEYWORD -> {
        state.setOwnerOnly(null);
        state.setCurrentStep(FilterStep.OWNER_ONLY);
      }
      case DONE -> {
        state.setQuery(null);
        state.setCurrentStep(FilterStep.KEYWORD);
      }
    }
    return state;
  }

  /**
   * Applies free-text keyword input from the user and advances the wizard to DONE.
   *
   * <p>Called when the user types a message while the wizard is at the KEYWORD step.
   * Blank text is treated as "no filter" (same as pressing "Пропустить").
   *
   * @param telegramId Telegram user identifier, never null
   * @param text       user-provided text, may be blank
   * @return updated state positioned at DONE
   */
  public SearchFilterState applyKeyword(Long telegramId, String text) {
    var state = states.computeIfAbsent(telegramId, id -> new SearchFilterState());
    state.setQuery(text == null || text.isBlank() ? null : text.strip());
    state.setCurrentStep(FilterStep.DONE);
    log.debug("Keyword applied: telegramId={}, query={}", telegramId, text);
    return state;
  }

  /**
   * Removes the wizard state for the given user, discarding all collected parameters.
   *
   * @param telegramId Telegram user identifier, never null
   */
  public void reset(Long telegramId) {
    states.remove(telegramId);
    log.debug("Filter wizard reset: telegramId={}", telegramId);
  }

  private String parsePropertyType(String value) {
    if (ALLOWED_PROPERTY_TYPES.contains(value)) {
      return value;
    }
    log.warn("Unknown property type value in callback: {}", value);
    return null;
  }

  private DealType parseDealType(String value) {
    try {
      return DealType.valueOf(value);
    } catch (IllegalArgumentException e) {
      log.warn("Unknown deal type value in callback: {}", value);
      return null;
    }
  }

  private Integer parseRooms(String value) {
    if ("4_PLUS".equals(value)) {
      return 4;
    }
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      log.warn("Unparseable rooms value in callback: {}", value);
      return null;
    }
  }

  private void applyPriceRange(SearchFilterState state, String value) {
    switch (value) {
      case "LOW" -> { state.setPriceMin(null); state.setPriceMax(PRICE_LOW_MAX); }
      case "MEDIUM" -> { state.setPriceMin(PRICE_MED_MIN); state.setPriceMax(PRICE_MED_MAX); }
      case "HIGH" -> { state.setPriceMin(PRICE_HIGH_MIN); state.setPriceMax(PRICE_HIGH_MAX); }
      case "PREMIUM" -> { state.setPriceMin(PRICE_PREMIUM_MIN); state.setPriceMax(null); }
      case VALUE_ANY -> { state.setPriceMin(null); state.setPriceMax(null); }
      // Unknown value: price stays null (no filter), step still advances — intentional graceful degradation
      default -> log.warn("Unknown price range value in callback: {}", value);
    }
  }
}
