package com.flatio.telegram.state;

import com.flatio.domain.listing.DealType;
import com.flatio.config.SellPriceFilterProperties;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * In-memory wizard that guides users through search filter selection.
 *
 * <p>State is stored in a Caffeine cache keyed by Telegram user ID, exposed as a {@link Map}
 * view so the existing atomic {@code compute}-based read-modify-write calls below keep working
 * unchanged. Entries expire {@value #STATE_TTL_MINUTES} minutes after last write and are bounded
 * by {@link #MAX_STATES}, so an abandoned wizard no longer occupies memory for the lifetime of
 * the JVM (issue #382).
 *
 * <p>Survives within a single JVM instance only — no Redis/DB persistence.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class SearchFilterWizard {

  /** Callback prefix used by all filter-related keyboard buttons. */
  public static final String CALLBACK_PREFIX = "FILTER";

  /** Callback value meaning "no filter applied" for a given step. */
  public static final String VALUE_ANY = "ANY";

  /** Callback value meaning "leave the KEYWORD step's current value untouched" (issue #523). */
  public static final String VALUE_KEEP = "KEEP";

  private static final Set<String> ALLOWED_PROPERTY_TYPES = Set.of("APARTMENT", "HOUSE", "ROOM");

  private static final long STATE_TTL_MINUTES = 30;
  private static final long MAX_STATES = 10_000;

  // Rent price thresholds (BYN/month)
  static final BigDecimal RENT_PRICE_LOW_MAX = BigDecimal.valueOf(1_000);
  static final BigDecimal RENT_PRICE_MED_MIN = BigDecimal.valueOf(1_000);
  static final BigDecimal RENT_PRICE_MED_MAX = BigDecimal.valueOf(2_000);
  static final BigDecimal RENT_PRICE_HIGH_MIN = BigDecimal.valueOf(2_000);
  static final BigDecimal RENT_PRICE_HIGH_MAX = BigDecimal.valueOf(4_000);
  static final BigDecimal RENT_PRICE_PREMIUM_MIN = BigDecimal.valueOf(4_000);

  private final SellPriceFilterProperties sellPriceProps;

  private final Map<Long, SearchFilterState> states = Caffeine.newBuilder()
      .expireAfterWrite(Duration.ofMinutes(STATE_TTL_MINUTES))
      .maximumSize(MAX_STATES)
      .<Long, SearchFilterState>build()
      .asMap();

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
   * Starts the wizard pre-filled with the given state, for editing an existing subscription's
   * criteria (issue #479) rather than starting a plain search from scratch.
   *
   * <p>Unlike {@link #start}, the caller supplies the initial state (already carrying the
   * subscription's current criteria and {@link SearchFilterState#getEditingSubscriptionId()}), so
   * the wizard steps show pre-selected values instead of blank ones.
   *
   * @param telegramId Telegram user identifier, never null
   * @param prefilled  state pre-populated with the subscription's criteria and editing marker, never null
   * @return the same state, positioned at the first step
   */
  public SearchFilterState startForEdit(Long telegramId, SearchFilterState prefilled) {
    prefilled.setCurrentStep(FilterStep.DEAL_TYPE);
    states.put(telegramId, prefilled);
    log.debug("Filter wizard started for subscription edit: telegramId={}, subscriptionId={}",
        telegramId, prefilled.getEditingSubscriptionId());
    return prefilled;
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
    // ConcurrentHashMap#compute holds this key's bin lock for the whole remapping call, so the
    // read-modify-write below is atomic — the bot handles each Telegram update on a pooled thread
    // with no ordering guarantee, and two rapid taps (or a Telegram-side retry) racing on the same
    // telegramId would otherwise be able to interleave setters and leave currentStep out of sync
    // with the fields it implies.
    return states.compute(telegramId, (id, existing) -> {
      SearchFilterState state = existing != null ? existing : new SearchFilterState();
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
          // KEEP (issue #523): "Пропустить" while editing a subscription that already has a
          // keyword leaves it untouched — only VALUE_ANY ("Очистить"/plain skip elsewhere)
          // clears it, same as every other step's "no value" semantics.
          if (!VALUE_KEEP.equals(value)) {
            state.setQuery(VALUE_ANY.equals(value) ? null : value);
          }
          state.setCurrentStep(FilterStep.DONE);
        }
        default -> log.warn("Unexpected step in applySelection: step={}", step);
      }
      log.debug("Filter step applied: telegramId={}, step={}, value={}", telegramId, step, value);
      return state;
    });
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
    // See applySelection for why this runs inside ConcurrentHashMap#compute rather than a
    // computeIfAbsent + synchronized(state) pair: the DEAL_TYPE case below replaces the state
    // wholesale (a "restart"), and doing that outside this atomic remapping call would let a
    // concurrent applySelection/stepBack/applyKeyword hold a reference to the object being
    // replaced, apply its update to it, and have that update silently lost once discarded here.
    return states.compute(telegramId, (id, existing) -> {
      SearchFilterState state = existing != null ? existing : new SearchFilterState();
      switch (state.getCurrentStep()) {
        case DEAL_TYPE -> {
          state = new SearchFilterState();
          log.debug("Filter wizard started: telegramId={}", telegramId);
        }
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
    });
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
    return states.compute(telegramId, (id, existing) -> {
      SearchFilterState state = existing != null ? existing : new SearchFilterState();
      state.setQuery(text == null || text.isBlank() ? null : text.strip());
      state.setCurrentStep(FilterStep.DONE);
      log.debug("Keyword applied: telegramId={}, hasQuery={}", telegramId, state.getQuery() != null);
      return state;
    });
  }

  /**
   * Applies a custom price range entered as free text at the PRICE step (issue #526), bypassing
   * the fixed presets. The caller ({@link com.flatio.telegram.callback.FilterCallbackHandler})
   * has already validated {@code priceMin}/{@code priceMax} (both positive, min ≤ max).
   *
   * @param telegramId Telegram user identifier, never null
   * @param priceMin   validated minimum price, never null
   * @param priceMax   validated maximum price, never null
   * @return updated state positioned at OWNER_ONLY
   */
  public SearchFilterState applyCustomPriceRange(Long telegramId, BigDecimal priceMin, BigDecimal priceMax) {
    return states.compute(telegramId, (id, existing) -> {
      SearchFilterState state = existing != null ? existing : new SearchFilterState();
      state.setPriceMin(priceMin);
      state.setPriceMax(priceMax);
      state.setCurrentStep(FilterStep.OWNER_ONLY);
      log.debug("Custom price range applied: telegramId={}, priceMin={}, priceMax={}", telegramId, priceMin, priceMax);
      return state;
    });
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
    boolean isSale = state.getDealType() == DealType.SELL;
    switch (value) {
      case "LOW" -> {
        state.setPriceMin(null);
        state.setPriceMax(isSale ? sellPriceProps.lowMax() : RENT_PRICE_LOW_MAX);
      }
      case "MEDIUM" -> {
        state.setPriceMin(isSale ? sellPriceProps.lowMax() : RENT_PRICE_MED_MIN);
        state.setPriceMax(isSale ? sellPriceProps.mediumMax() : RENT_PRICE_MED_MAX);
      }
      case "HIGH" -> {
        state.setPriceMin(isSale ? sellPriceProps.mediumMax() : RENT_PRICE_HIGH_MIN);
        state.setPriceMax(isSale ? sellPriceProps.highMax() : RENT_PRICE_HIGH_MAX);
      }
      case "PREMIUM" -> {
        state.setPriceMin(isSale ? sellPriceProps.highMax() : RENT_PRICE_PREMIUM_MIN);
        state.setPriceMax(null);
      }
      case VALUE_ANY -> { state.setPriceMin(null); state.setPriceMax(null); }
      // Unknown value: price stays null (no filter), step still advances — intentional graceful degradation
      default -> log.warn("Unknown price range value in callback: {}", value);
    }
  }
}
