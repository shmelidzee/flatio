package com.flatio.integration.core;

import com.flatio.integration.core.RawListing;
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
   * @return a partially populated listing; caller must set source, currency, country, status, and dedupHash
   */
  @Mapping(target = "id", ignore = true)
  @Mapping(target = "source", ignore = true)
  @Mapping(target = "currency", ignore = true)
  @Mapping(target = "country", ignore = true)
  @Mapping(target = "status", ignore = true)
  @Mapping(target = "dedupHash", ignore = true)
  @Mapping(target = "missedSyncsCount", ignore = true)
  @Mapping(target = "geocodingFailedAttempts", ignore = true)
  @Mapping(target = "areaLivingM2", ignore = true)
  @Mapping(target = "areaKitchenM2", ignore = true)
  @Mapping(target = "district", ignore = true)
  @Mapping(target = "createdAt", ignore = true)
  @Mapping(target = "updatedAt", ignore = true)
  @Mapping(target = "version", ignore = true)
  @Mapping(source = "dealType", target = "dealType")
  @Mapping(target = "priceUnit", ignore = true)
  @Mapping(target = "repostedFrom", ignore = true)
  @Mapping(target = "lastRepostedAt", ignore = true)
  @Mapping(target = "photoUrl", expression = "java(extractFirstPhotoUrl(raw))")
  @Mapping(source = "priceByn", target = "priceByn")
  @Mapping(source = "isNegotiable", target = "isNegotiable")
  Listing toEntity(RawListing raw);

  /**
   * Updates mutable fields of an existing {@link Listing} from a {@link RawListing}.
   *
   * <p>Identity ({@code id}), ownership ({@code source}, {@code country}),
   * financial ({@code currency}), and computed ({@code status}, {@code dedupHash},
   * {@code missedSyncsCount}, {@code priceUnit}) fields are not touched — the caller
   * is responsible for them.
   * {@code priceUsd} is mapped directly from the raw listing.
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
  @Mapping(target = "missedSyncsCount", ignore = true)
  @Mapping(target = "geocodingFailedAttempts", ignore = true)
  @Mapping(target = "areaLivingM2", ignore = true)
  @Mapping(target = "areaKitchenM2", ignore = true)
  @Mapping(target = "district", ignore = true)
  @Mapping(target = "createdAt", ignore = true)
  @Mapping(target = "updatedAt", ignore = true)
  @Mapping(target = "version", ignore = true)
  @Mapping(target = "publishedAt", ignore = true)
  @Mapping(source = "dealType", target = "dealType")
  @Mapping(target = "priceUnit", ignore = true)
  @Mapping(target = "repostedFrom", ignore = true)
  @Mapping(target = "lastRepostedAt", ignore = true)
  @Mapping(target = "photoUrl", expression = "java(extractFirstPhotoUrl(raw))")
  @Mapping(source = "priceByn", target = "priceByn")
  @Mapping(source = "isNegotiable", target = "isNegotiable")
  void updateEntity(RawListing raw, @MappingTarget Listing listing);

  /**
   * Returns the first photo URL from the raw listing's photo list, or null if none available.
   *
   * @param raw the raw listing, must not be null
   * @return first photo URL, or null
   */
  default String extractFirstPhotoUrl(RawListing raw) {
    return raw.photoUrls() != null && !raw.photoUrls().isEmpty() ? raw.photoUrls().get(0) : null;
  }

  /**
   * Converts a deal type string from a connector to the {@link DealType} enum.
   *
   * <p>Returns {@code null} for null or unrecognised values without throwing.
   * Callers must guard against null before persisting (see {@link DealType#isKnown(String)}).
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
