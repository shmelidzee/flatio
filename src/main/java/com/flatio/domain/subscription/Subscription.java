package com.flatio.domain.subscription;

import com.flatio.domain.user.User;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

/**
 * A saved search filter that notifies its owner when matching listings appear or change.
 *
 * <p>{@code searchCriteria} stores the filter as a JSON document, mirroring the shape of
 * {@code ListingSearchCriteria} used by the search API. {@code triggers} controls which
 * listing events (new match, price drop, repost, reactivation) produce a notification.
 */
@Entity
@Table(name = "subscriptions")
@Getter
@Setter
public class Subscription {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "user_id", nullable = false)
  private User user;

  @Column(nullable = false, length = 255)
  private String name;

  @Column(name = "is_active", nullable = false)
  private boolean active = true;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "search_criteria", nullable = false, columnDefinition = "jsonb")
  private Map<String, Object> searchCriteria;

  // BatchSize avoids N+1 when loading a page of subscriptions: triggers for all subscriptions
  // in the batch are fetched with one extra IN-clause query instead of one query per subscription.
  @ElementCollection(fetch = FetchType.LAZY)
  @CollectionTable(name = "subscription_triggers", joinColumns = @JoinColumn(name = "subscription_id"))
  @BatchSize(size = 20)
  @Enumerated(EnumType.STRING)
  @Column(name = "trigger_type", nullable = false, length = 30)
  private Set<TriggerType> triggers = new HashSet<>();

  @Enumerated(EnumType.STRING)
  @Column(name = "delivery_mode", nullable = false, length = 20)
  private DeliveryMode deliveryMode;

  @Enumerated(EnumType.STRING)
  @Column(name = "channel_type", nullable = false, length = 20)
  private SubscriptionChannelType channelType;

  @Column(name = "price_drop_threshold", precision = 5, scale = 2)
  private BigDecimal priceDropThreshold;

  @Column(name = "quiet_hours_start")
  private LocalTime quietHoursStart;

  @Column(name = "quiet_hours_end")
  private LocalTime quietHoursEnd;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;
}
