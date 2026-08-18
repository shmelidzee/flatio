package com.flatio.domain.audit;

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
 * Audit record for a single admin action (status change, role change, source reconfiguration).
 *
 * <p>Records are append-only — they are never modified after insertion.
 */
@Entity
@Table(name = "admin_audit_log")
@Getter
@Setter
public class AdminAuditLog {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "admin_id", nullable = false)
  private Long adminId;

  @Column(nullable = false, length = 100)
  private String action;

  @Enumerated(EnumType.STRING)
  @Column(name = "object_type", nullable = false, length = 50)
  private AdminAuditObjectType objectType;

  @Column(name = "object_id", length = 100)
  private String objectId;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;
}
