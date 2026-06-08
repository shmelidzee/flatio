package com.flatio.web.mapper;

import com.flatio.domain.listing.Listing;
import com.flatio.web.dto.ListingResponse;
import com.flatio.web.dto.ListingSummaryResponse;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * MapStruct mapper for converting {@link Listing} entities to REST API response DTOs.
 */
@Mapper(componentModel = "spring")
public interface ListingMapper {

  /**
   * Converts a Listing entity to a full response DTO.
   *
   * @param listing the listing entity, must not be null
   * @return full listing response DTO, never null
   */
  @Mapping(source = "source.code", target = "sourceId")
  @Mapping(source = "currency.code", target = "currency")
  ListingResponse toResponse(Listing listing);

  /**
   * Converts a Listing entity to a summary response DTO for list displays.
   *
   * <p>The {@code photoUrl} field is not populated by this mapper; photo retrieval
   * requires a separate query and is handled by the calling service.
   *
   * @param listing the listing entity, must not be null
   * @return summary listing response DTO, never null
   */
  @Mapping(source = "source.code", target = "sourceId")
  @Mapping(source = "currency.code", target = "currency")
  @Mapping(target = "photoUrl", ignore = true)
  ListingSummaryResponse toSummaryResponse(Listing listing);

  /**
   * Converts a list of Listing entities to a list of summary response DTOs.
   *
   * @param listings the list of listing entities, must not be null
   * @return list of summary DTOs, never null
   */
  List<ListingSummaryResponse> toSummaryResponseList(List<Listing> listings);
}
