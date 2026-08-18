package com.flatio.web.mapper;

import com.flatio.domain.user.User;
import com.flatio.web.dto.AdminUserResponse;
import org.mapstruct.Mapper;

/**
 * MapStruct mapper for converting {@link User} entities to admin API response DTOs.
 */
@Mapper(componentModel = "spring")
public interface AdminUserMapper {

  AdminUserResponse toResponse(User user);
}
