package com.flatio.telegram.state;

import com.flatio.domain.listing.DealType;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

/**
 * Mutable in-memory state of the search filter wizard for a single user session.
 *
 * <p>Holds all parameters collected across wizard steps. Fields are null until
 * the user selects a value or explicitly chooses "any".
 */
@Getter
@Setter
public class SearchFilterState {

  private FilterStep currentStep = FilterStep.DEAL_TYPE;
  private DealType dealType;
  /** One of: APARTMENT, HOUSE, ROOM — null means no filter. */
  private String propertyType;
  private Integer rooms;
  private BigDecimal priceMin;
  private BigDecimal priceMax;
  /** True = only owner listings; null = no filter (show all). */
  private Boolean ownerOnly;
  /** Free-text keyword query; null means no text filter. */
  private String query;
}
