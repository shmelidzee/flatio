package com.flatio.domain.source;

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
 * Audit record for a single connector sync run.
 *
 * <p>Records are append-only — they are never modified after insertion.
 * Used by {@code DataFreshnessHealthIndicator} to determine how recently
 * a successful sync was completed.
 */
@Entity
@Table(name = "sync_runs")
@Getter
@Setter
public class SyncRun {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 50)
  private String sourceId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 10)
  private SyncType syncType;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 10)
  private SyncRunStatus status;

  @Column(nullable = false)
  private Instant startedAt;

  @Column(nullable = false)
  private Instant finishedAt;

  @Column(nullable = false)
  private int listingsFetched;

  @Column(nullable = false)
  private int listingsAdded;

  @Column(nullable = false)
  private int listingsUpdated;

  @Column(nullable = false)
  private int listingsErrors;

  @CreationTimestamp
  @Column(nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(nullable = false)
  private Instant updatedAt;
}
