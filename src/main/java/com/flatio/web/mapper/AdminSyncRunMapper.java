package com.flatio.web.mapper;

import com.flatio.domain.source.SyncRun;
import com.flatio.web.dto.AdminSyncRunResponse;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * MapStruct mapper for converting {@link SyncRun} entities to admin API response DTOs.
 */
@Mapper(componentModel = "spring")
public interface AdminSyncRunMapper {

  /**
   * Converts a sync run entity to an admin response DTO, computing the run duration.
   *
   * @param syncRun the sync run entity, must not be null
   * @return admin sync run response DTO, never null
   */
  @Mapping(target = "durationMs", expression = "java(java.time.Duration.between("
      + "syncRun.getStartedAt(), syncRun.getFinishedAt()).toMillis())")
  AdminSyncRunResponse toResponse(SyncRun syncRun);

  /**
   * Converts a list of sync run entities to a list of admin response DTOs.
   *
   * @param syncRuns the sync run entities, must not be null
   * @return list of admin response DTOs, never null
   */
  List<AdminSyncRunResponse> toResponseList(List<SyncRun> syncRuns);
}
