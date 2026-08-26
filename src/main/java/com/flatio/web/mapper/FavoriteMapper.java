package com.flatio.web.mapper;

import com.flatio.domain.favorite.Favorite;
import com.flatio.domain.listing.ListingStatus;
import com.flatio.web.dto.FavoriteResponse;
import java.math.BigDecimal;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * MapStruct mapper for converting {@link Favorite} entities to REST API response DTOs.
 */
@Mapper(componentModel = "spring", uses = ListingMapper.class, imports = ListingStatus.class)
public interface FavoriteMapper {

  /**
   * Converts a favorite entity to its response DTO, computing price-change and availability
   * indicators from the favorited listing's current state.
   *
   * <p>{@code priceDelta} is read from {@link Favorite#getPriceChange()}, a database-computed
   * column, rather than recalculated here.
   *
   * @param favorite the favorite entity, must not be null
   * @return response DTO, never null
   */
  @Mapping(source = "favorite.listing", target = "listing")
  @Mapping(source = "favorite.listing.price", target = "currentPrice")
  @Mapping(source = "favorite.priceChange", target = "priceDelta")
  @Mapping(target = "priceChanged", expression = "java(isPriceChanged(favorite.getPriceChange()))")
  @Mapping(target = "listingInactive",
      expression = "java(favorite.getListing().getStatus() == ListingStatus.INACTIVE)")
  FavoriteResponse toResponse(Favorite favorite);

  /**
   * Returns true when the computed price delta is non-null and non-zero.
   *
   * @param priceDelta the database-computed price change, may be null
   * @return true if the price changed since the listing was favorited
   */
  default boolean isPriceChanged(BigDecimal priceDelta) {
    return priceDelta != null && priceDelta.compareTo(BigDecimal.ZERO) != 0;
  }
}
