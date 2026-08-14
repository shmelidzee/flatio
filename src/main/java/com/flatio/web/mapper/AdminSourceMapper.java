package com.flatio.web.mapper;

import com.flatio.domain.source.Source;
import com.flatio.web.dto.AdminSourceResponse;
import java.time.Instant;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * MapStruct mapper for converting {@link Source} entities to admin API response DTOs.
 */
@Mapper(componentModel = "spring")
public interface AdminSourceMapper {

  /**
   * Converts a source entity to an admin response, enriched with its last successful sync time.
   *
   * <p>{@code lastSyncAt} is not stored on the entity — it is derived by the service from
   * {@code SyncRunService} and passed in separately.
   *
   * @param source     the source entity, must not be null
   * @param lastSyncAt finish time of the most recent successful sync, or null if never synced
   * @return admin source response DTO, never null
   */
  @Mapping(source = "source.code", target = "sourceId")
  @Mapping(source = "source.name", target = "displayName")
  @Mapping(source = "source.country.code", target = "countryCode")
  @Mapping(source = "source.active", target = "enabled")
  @Mapping(source = "lastSyncAt", target = "lastSyncAt")
  AdminSourceResponse toResponse(Source source, Instant lastSyncAt);
}
