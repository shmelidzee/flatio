package com.flatio.web.mapper;

import com.flatio.domain.blacklist.BlacklistEntry;
import com.flatio.web.dto.BlacklistEntryResponse;
import org.mapstruct.Mapper;

/**
 * MapStruct mapper for converting {@link BlacklistEntry} entities to REST API response DTOs.
 */
@Mapper(componentModel = "spring")
public interface BlacklistEntryMapper {

  /**
   * Converts a blacklist entry entity to its response DTO.
   *
   * @param entry the blacklist entry entity, must not be null
   * @return response DTO, never null
   */
  BlacklistEntryResponse toResponse(BlacklistEntry entry);
}
