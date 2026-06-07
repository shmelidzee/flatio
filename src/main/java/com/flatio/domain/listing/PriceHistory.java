package com.flatio.domain.listing;

import com.flatio.domain.currency.Currency;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "price_history")
@Getter
@Setter
public class PriceHistory {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "listing_id", nullable = false)
  private Listing listing;

  @Column(nullable = false, precision = 15, scale = 2)
  private BigDecimal price;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "currency_id", nullable = false)
  private Currency currency;

  @Column(name = "recorded_at", nullable = false, updatable = false)
  private Instant recordedAt;

  @PrePersist
  protected void onInsert() {
    if (recordedAt == null) {
      recordedAt = Instant.now();
    }
  }
}
