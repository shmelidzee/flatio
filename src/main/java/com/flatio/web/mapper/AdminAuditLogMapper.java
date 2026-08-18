package com.flatio.web.mapper;

import com.flatio.domain.audit.AdminAuditLog;
import com.flatio.web.dto.AdminAuditLogResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * MapStruct mapper for converting {@link AdminAuditLog} entities to admin API response DTOs.
 */
@Mapper(componentModel = "spring")
public interface AdminAuditLogMapper {

  /**
   * Converts an audit log entry to an admin response, enriched with the acting admin's display name.
   *
   * <p>{@code adminDisplayName} is not stored on the entity — it is resolved by the service from
   * {@code UserRepository} and passed in separately, batched across a whole page to avoid N+1.
   *
   * @param entry            the audit log entity, must not be null
   * @param adminDisplayName display name of the acting admin, or null if the user no longer exists
   * @return admin audit log response DTO, never null
   */
  @Mapping(source = "adminDisplayName", target = "adminDisplayName")
  AdminAuditLogResponse toResponse(AdminAuditLog entry, String adminDisplayName);
}
