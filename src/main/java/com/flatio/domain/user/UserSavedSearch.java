package com.flatio.domain.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * Stores the last search filter a Telegram user applied.
 *
 * <p>One row per user (enforced by unique constraint on {@code telegram_user_id}).
 * All filter fields are nullable — a null value means the dimension was not restricted.
 */
@Entity
@Table(name = "user_saved_searches")
@Getter
@Setter
@NoArgsConstructor
public class UserSavedSearch {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "telegram_user_id", nullable = false, unique = true)
  private Long telegramUserId;

  @Column(name = "region_code", length = 20)
  private String regionCode;

  @Column(name = "price_min", precision = 15, scale = 2)
  private BigDecimal priceMin;

  @Column(name = "price_max", precision = 15, scale = 2)
  private BigDecimal priceMax;

  @Column(name = "rooms_min")
  private Integer roomsMin;

  @Column(name = "rooms_max")
  private Integer roomsMax;

  @Column(name = "property_type", length = 50)
  private String propertyType;

  @Column(name = "is_owner")
  private Boolean isOwner;

  @Column(length = 500)
  private String keyword;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;
}
