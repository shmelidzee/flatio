package com.flatio.domain.favorite;

import com.flatio.domain.listing.Listing;
import com.flatio.domain.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Formula;

/**
 * A listing a user has marked as a favorite.
 *
 * <p>{@code priceAtAdd} captures the listing's price at the moment it was favorited, so the API
 * can later report whether the price changed. {@code priceChange} is a read-only, database-computed
 * column (current listing price minus {@code priceAtAdd}) that lets the favorites list be sorted
 * by "changed the most" without loading every listing into memory; it is never written by the
 * application. Uniqueness of {@code (user, listing)} is enforced by a database constraint rather
 * than an application-level check, so a favorite is never duplicated.
 */
@Entity
@Table(
    name = "favorites",
    uniqueConstraints = @UniqueConstraint(name = "uq_favorites_user_listing", columnNames = {"user_id", "listing_id"})
)
@Getter
@Setter
public class Favorite {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "user_id", nullable = false)
  private User user;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "listing_id", nullable = false)
  private Listing listing;

  @Column(name = "price_at_add", precision = 15, scale = 2)
  private BigDecimal priceAtAdd;

  @Formula("(SELECT l.price FROM listings l WHERE l.id = listing_id) - price_at_add")
  @Setter(AccessLevel.NONE)
  private BigDecimal priceChange;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;
}
