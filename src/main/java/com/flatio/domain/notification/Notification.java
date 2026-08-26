package com.flatio.domain.notification;

import com.flatio.domain.listing.Listing;
import com.flatio.domain.subscription.Subscription;
import com.flatio.domain.subscription.TriggerType;
import jakarta.persistence.Column;
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
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * A single notification raised when a listing change matches a subscription's search filter
 * and trigger configuration (FR-SUB-4, FR-SUB-9).
 *
 * <p>The {@code (subscription, listing, triggerType)} triple is unique so the same subscription
 * is never notified twice about the same event on the same listing (FR-SUB-8). This is enforced
 * both here at the database level and, ahead of insert, in {@code NotificationTriggerServiceImpl}.
 */
@Entity
@Table(
    name = "notifications",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_notifications_subscription_listing_trigger",
        columnNames = {"subscription_id", "listing_id", "trigger_type"}
    )
)
@Getter
@Setter
public class Notification {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "subscription_id", nullable = false)
  private Subscription subscription;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "listing_id", nullable = false)
  private Listing listing;

  @Enumerated(EnumType.STRING)
  @Column(name = "trigger_type", nullable = false, length = 30)
  private TriggerType triggerType;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 10)
  private NotificationStatus status = NotificationStatus.PENDING;

  @Column(name = "sent_at")
  private Instant sentAt;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;
}
