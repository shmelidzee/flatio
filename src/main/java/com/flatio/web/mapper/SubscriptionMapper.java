package com.flatio.web.mapper;

import com.flatio.domain.subscription.Subscription;
import com.flatio.web.dto.CreateSubscriptionRequest;
import com.flatio.web.dto.SubscriptionResponse;
import com.flatio.web.dto.UpdateSubscriptionRequest;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

/**
 * MapStruct mapper for converting between {@link Subscription} entities and their REST DTOs.
 */
@Mapper(componentModel = "spring", uses = SearchCriteriaJsonMapper.class)
public interface SubscriptionMapper {

  /**
   * Converts a subscription entity to its full API response DTO.
   *
   * @param subscription the entity, must not be null
   * @return response DTO, never null
   */
  SubscriptionResponse toResponse(Subscription subscription);

  /**
   * Builds a new subscription entity from a create request.
   *
   * <p>{@code id}, {@code user}, {@code active}, {@code createdAt}, and {@code updatedAt} are set
   * by the service, not by this mapper.
   *
   * @param request the create request, must not be null
   * @return a new, unsaved subscription entity
   */
  @Mapping(target = "id", ignore = true)
  @Mapping(target = "user", ignore = true)
  @Mapping(target = "active", ignore = true)
  @Mapping(target = "createdAt", ignore = true)
  @Mapping(target = "updatedAt", ignore = true)
  Subscription toEntity(CreateSubscriptionRequest request);

  /**
   * Applies an update request's name, filter, and delivery settings onto an existing entity.
   *
   * <p>Does not touch {@code id}, {@code user}, or {@code active} — active state is only
   * changed via pause/resume.
   *
   * @param request the update request, must not be null
   * @param entity  the entity to update in place
   */
  @Mapping(target = "id", ignore = true)
  @Mapping(target = "user", ignore = true)
  @Mapping(target = "active", ignore = true)
  @Mapping(target = "createdAt", ignore = true)
  @Mapping(target = "updatedAt", ignore = true)
  void updateEntity(UpdateSubscriptionRequest request, @MappingTarget Subscription entity);
}
