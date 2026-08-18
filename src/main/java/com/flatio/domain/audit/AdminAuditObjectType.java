package com.flatio.domain.audit;

/**
 * Type of resource an admin action in {@link AdminAuditLog} was performed on.
 */
public enum AdminAuditObjectType {
  LISTING,
  SOURCE,
  USER
}
