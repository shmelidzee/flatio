package com.flatio.service;

import com.flatio.domain.listing.DealType;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DedupHashServiceImplTest {

  private final DedupHashService dedupHashService = new DedupHashServiceImpl();

  // -------------------------------------------------------------------------
  // computeDedupHash — basic behaviour
  // -------------------------------------------------------------------------

  @Test
  void should_return_64_char_hex_hash() {
    // When
    var hash = dedupHashService.computeDedupHash("Минск, Немига 5", 2, BigDecimal.valueOf(55.5), DealType.RENT);

    // Then
    assertThat(hash).hasSize(64);
    assertThat(hash).matches("[0-9a-f]+");
  }

  @Test
  void should_return_same_hash_for_identical_inputs() {
    // Given
    var hash1 = dedupHashService.computeDedupHash("Минск, Немига 5", 2, BigDecimal.valueOf(55.5), DealType.RENT);

    // When
    var hash2 = dedupHashService.computeDedupHash("Минск, Немига 5", 2, BigDecimal.valueOf(55.5), DealType.RENT);

    // Then
    assertThat(hash1).isEqualTo(hash2);
  }

  @Test
  void should_return_different_hash_when_address_differs() {
    // Given
    var hash1 = dedupHashService.computeDedupHash("Минск, Немига 5", 2, BigDecimal.valueOf(55.5), DealType.RENT);

    // When
    var hash2 = dedupHashService.computeDedupHash("Минск, Немига 6", 2, BigDecimal.valueOf(55.5), DealType.RENT);

    // Then
    assertThat(hash1).isNotEqualTo(hash2);
  }

  @Test
  void should_return_different_hash_when_deal_type_differs() {
    // Given
    var hashRent = dedupHashService.computeDedupHash("Немига 5", 2, BigDecimal.valueOf(55), DealType.RENT);

    // When
    var hashSell = dedupHashService.computeDedupHash("Немига 5", 2, BigDecimal.valueOf(55), DealType.SELL);

    // Then
    assertThat(hashRent).isNotEqualTo(hashSell);
  }

  // -------------------------------------------------------------------------
  // computeDedupHash — normalization
  // -------------------------------------------------------------------------

  @Test
  void should_produce_same_hash_regardless_of_address_case() {
    // Given — same address with different cases
    var hash1 = dedupHashService.computeDedupHash("МИНСК, НЕМИГА 5", 2, BigDecimal.valueOf(55), DealType.RENT);

    // When
    var hash2 = dedupHashService.computeDedupHash("минск, немига 5", 2, BigDecimal.valueOf(55), DealType.RENT);

    // Then — lowercase normalization makes them equal
    assertThat(hash1).isEqualTo(hash2);
  }

  @Test
  void should_produce_same_hash_when_address_has_extra_whitespace() {
    // Given
    var hash1 = dedupHashService.computeDedupHash("Немига  5", 2, BigDecimal.valueOf(55), DealType.RENT);

    // When — extra spaces collapsed
    var hash2 = dedupHashService.computeDedupHash("Немига 5", 2, BigDecimal.valueOf(55), DealType.RENT);

    // Then
    assertThat(hash1).isEqualTo(hash2);
  }

  @Test
  void should_produce_same_hash_when_address_has_leading_trailing_spaces() {
    // Given
    var hash1 = dedupHashService.computeDedupHash("  Немига 5  ", 2, BigDecimal.valueOf(55), DealType.RENT);

    // When
    var hash2 = dedupHashService.computeDedupHash("Немига 5", 2, BigDecimal.valueOf(55), DealType.RENT);

    // Then
    assertThat(hash1).isEqualTo(hash2);
  }

  @Test
  void should_produce_same_hash_for_equivalent_bigdecimal_values() {
    // Given — same area, different BigDecimal representation
    var hash1 = dedupHashService.computeDedupHash("Немига 5", 2, new BigDecimal("55.50"), DealType.RENT);

    // When
    var hash2 = dedupHashService.computeDedupHash("Немига 5", 2, new BigDecimal("55.5"), DealType.RENT);

    // Then — stripTrailingZeros normalizes them
    assertThat(hash1).isEqualTo(hash2);
  }

  // -------------------------------------------------------------------------
  // computeDedupHash — null handling
  // -------------------------------------------------------------------------

  @Test
  void should_handle_null_address_without_exception() {
    // When
    var hash = dedupHashService.computeDedupHash(null, 2, BigDecimal.valueOf(55), DealType.RENT);

    // Then
    assertThat(hash).isNotNull().hasSize(64);
  }

  @Test
  void should_handle_null_rooms_without_exception() {
    // When
    var hash = dedupHashService.computeDedupHash("Немига 5", null, BigDecimal.valueOf(55), DealType.RENT);

    // Then
    assertThat(hash).isNotNull().hasSize(64);
  }

  @Test
  void should_handle_null_area_without_exception() {
    // When
    var hash = dedupHashService.computeDedupHash("Немига 5", 2, null, DealType.RENT);

    // Then
    assertThat(hash).isNotNull().hasSize(64);
  }

  @Test
  void should_produce_different_hash_for_different_null_positions() {
    // Given — null address vs null rooms should not collide
    var hashNullAddress = dedupHashService.computeDedupHash(null, 2, BigDecimal.valueOf(55), DealType.RENT);

    // When
    var hashNullRooms = dedupHashService.computeDedupHash("", null, BigDecimal.valueOf(55), DealType.RENT);

    // Then — field separator prevents collision between adjacent nulls
    assertThat(hashNullAddress).isNotEqualTo(hashNullRooms);
  }
}
