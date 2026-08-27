package com.flatio.domain.currency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * A daily exchange rate snapshot: how many units of {@code targetCurrency} one unit of
 * {@code baseCurrency} is worth, as published by the National Bank of Belarus (NBRb) for the
 * given {@code effectiveDate}.
 *
 * <p>{@code (baseCurrency, targetCurrency, effectiveDate)} is unique — one rate per currency
 * pair per day. History is kept (not overwritten in place) so a listing's stored price can, in
 * principle, be re-expressed against the rate that was current at any past date; the sync job
 * only ever inserts today's row.
 */
@Entity
@Table(
    name = "exchange_rates",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_exchange_rates_base_target_date", columnNames = {"base_currency", "target_currency", "effective_date"})
)
@Getter
@Setter
public class ExchangeRate {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "base_currency", nullable = false, length = 10)
  private String baseCurrency;

  @Column(name = "target_currency", nullable = false, length = 10)
  private String targetCurrency;

  @Column(nullable = false, precision = 15, scale = 6)
  private BigDecimal rate;

  @Column(name = "effective_date", nullable = false)
  private LocalDate effectiveDate;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;
}
