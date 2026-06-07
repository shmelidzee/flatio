package com.flatio.service;

import com.flatio.connector.core.RawListing;
import com.flatio.domain.listing.DealType;
import com.flatio.domain.listing.Listing;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

/**
 * Maps raw connector output to domain {@link Listing} entities.
 *
 * <p>Context-dependent fields ({@code source}, {@code currency}, {@code country},
 * {@code status}, {@code dedupHash}) are intentionally left unmapped and must
 * be populated by the caller after mapping.
 */
@Mapper(componentModel = "spring")
public interface RawListingMapper {

  /**
   * Maps a {@link RawListing} to a new {@link Listing}.
   *
   * @param raw the raw listing from a connector, must not be null
   * @return a partially populated listing; caller must set source, currency, country, status, dedupHash
   */
  @Mapping(target = "id", ignore = true)
  @Mapping(target = "source", ignore = true)
  @Mapping(target = "currency", ignore = true)
  @Mapping(target = "country", ignore = true)
  @Mapping(target = "status", ignore = true)
  @Mapping(target = "dedupHash", ignore = true)
  @Mapping(target = "priceUsd", ignore = true)
  @Mapping(target = "areaLivingM2", ignore = true)
  @Mapping(target = "areaKitchenM2", ignore = true)
  @Mapping(target = "district", ignore = true)
  @Mapping(target = "createdAt", ignore = true)
  @Mapping(target = "updatedAt", ignore = true)
  @Mapping(source = "dealType", target = "dealType")
  Listing toEntity(RawListing raw);

  /**
   * Updates mutable fields of an existing {@link Listing} from a {@link RawListing}.
   *
   * <p>Identity ({@code id}), ownership ({@code source}, {@code country}),
   * financial ({@code currency}, {@code priceUsd}), and computed ({@code status},
   * {@code dedupHash}) fields are not touched — the caller is responsible for them.
   *
   * @param raw     updated raw listing data from a connector, must not be null
   * @param listing the existing listing to update in place, must not be null
   */
  @Mapping(target = "id", ignore = true)
  @Mapping(target = "source", ignore = true)
  @Mapping(target = "currency", ignore = true)
  @Mapping(target = "country", ignore = true)
  @Mapping(target = "status", ignore = true)
  @Mapping(target = "dedupHash", ignore = true)
  @Mapping(target = "priceUsd", ignore = true)
  @Mapping(target = "areaLivingM2", ignore = true)
  @Mapping(target = "areaKitchenM2", ignore = true)
  @Mapping(target = "district", ignore = true)
  @Mapping(target = "createdAt", ignore = true)
  @Mapping(target = "updatedAt", ignore = true)
  @Mapping(source = "dealType", target = "dealType")
  void updateEntity(RawListing raw, @MappingTarget Listing listing);

  /**
   * Converts a deal type string from a connector to the {@link DealType} enum.
   *
   * @param dealType the raw string value, may be null or unrecognised
   * @return the matching enum constant, or {@code null} if the input is null or unrecognised
   */
  default DealType toDealType(String dealType) {
    if (dealType == null) {
      return null;
    }
    try {
      return DealType.valueOf(dealType.toUpperCase());
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
