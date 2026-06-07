package com.flatio.service;

import com.flatio.domain.listing.DealType;
import java.math.BigDecimal;

/**
 * Computes SHA-256 deduplication hashes for listings based on identity fields.
 */
public interface DedupHashService {

  /**
   * Computes the SHA-256 deduplication hash for a listing's key identity fields.
   *
   * <p>The hash is derived from {@code address}, {@code rooms}, {@code areaTotalM2},
   * and {@code dealType} after normalization (lowercase, trim, collapse whitespace).
   * Null fields are treated as empty strings.
   *
   * @param address     the listing's street address, may be null
   * @param rooms       the number of rooms, may be null
   * @param areaTotalM2 the total area in square metres, may be null
   * @param dealType    the deal type (RENT or SELL), may be null
   * @return 64-character lowercase hex SHA-256 hash, never null
   */
  String computeDedupHash(String address, Integer rooms, BigDecimal areaTotalM2, DealType dealType);
}
