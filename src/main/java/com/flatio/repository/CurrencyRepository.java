package com.flatio.repository;

import com.flatio.domain.currency.Currency;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CurrencyRepository extends JpaRepository<Currency, Long> {

  /**
   * Finds a currency by its ISO code.
   *
   * @param code the ISO currency code (e.g., "BYN", "USD")
   * @return the currency if found, or empty
   */
  Optional<Currency> findByCode(String code);
}
