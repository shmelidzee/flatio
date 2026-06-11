package com.flatio.web.mapper;

import com.flatio.domain.listing.Listing;
import com.flatio.domain.listing.PriceHistory;
import com.flatio.web.dto.ListingResponse;
import com.flatio.web.dto.ListingSummaryResponse;
import com.flatio.web.dto.PriceHistoryEntry;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * MapStruct mapper for converting {@link Listing} entities to REST API response DTOs.
 */
@Mapper(componentModel = "spring")
public interface ListingMapper {

  /**
   * Converts a Listing entity and its pre-fetched price history to a full response DTO.
   *
   * <p>Price history must be fetched separately by the service and passed here to avoid
   * lazy-load issues and keep the Listing entity free of bidirectional associations.
   *
   * @param listing      the listing entity, must not be null
   * @param priceHistory price history entries, newest first; may be empty, never null
   * @return full listing response DTO, never null
   */
  @Mapping(source = "listing.source.code", target = "sourceId")
  @Mapping(source = "listing.currency.code", target = "currency")
  @Mapping(source = "priceHistory", target = "priceHistory")
  ListingResponse toResponse(Listing listing, List<PriceHistoryEntry> priceHistory);

  /**
   * Converts a Listing entity to a summary response DTO for list displays.
   *
   * @param listing the listing entity, must not be null
   * @return summary listing response DTO, never null
   */
  @Mapping(source = "source.code", target = "sourceId")
  @Mapping(source = "currency.code", target = "currency")
  @Mapping(source = "photoUrl", target = "photoUrl")
  @Mapping(source = "priceUsd", target = "priceUsd")
  @Mapping(source = "propertyType", target = "propertyType")
  ListingSummaryResponse toSummaryResponse(Listing listing);

  /**
   * Converts a list of Listing entities to a list of summary response DTOs.
   *
   * @param listings the list of listing entities, must not be null
   * @return list of summary DTOs, never null
   */
  List<ListingSummaryResponse> toSummaryResponseList(List<Listing> listings);

  /**
   * Converts a {@link PriceHistory} entity to a {@link PriceHistoryEntry} DTO.
   *
   * @param priceHistory the price history entity, must not be null
   * @return price history entry DTO, never null
   */
  @Mapping(source = "currency.code", target = "currency")
  PriceHistoryEntry toHistoryEntry(PriceHistory priceHistory);
}
