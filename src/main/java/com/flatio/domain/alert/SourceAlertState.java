package com.flatio.domain.alert;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * Tracks whether a source-health alert (issue #419) is currently active, for deduplication
 * (an already-active alert is re-notified only after the configured cooldown, not on every
 * check) and recovery detection (a row flips {@code active} back to {@code false} once the
 * underlying rule stops matching, which is what triggers the "resolved" notification).
 *
 * <p>One row per {@code (sourceId, alertType)} pair — see the unique constraint in
 * {@code V64__create_source_alert_state.sql}.
 */
@Entity
@Table(name = "source_alert_state")
@Getter
@Setter
public class SourceAlertState {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 50)
  private String sourceId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 30)
  private AlertType alertType;

  @Column(nullable = false)
  private boolean active;

  @Column(nullable = false)
  private Instant firstTriggeredAt;

  @Column(nullable = false)
  private Instant lastNotifiedAt;

  @CreationTimestamp
  @Column(nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(nullable = false)
  private Instant updatedAt;
}
