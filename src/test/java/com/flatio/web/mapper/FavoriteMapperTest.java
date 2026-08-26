package com.flatio.web.mapper;

import com.flatio.domain.favorite.Favorite;
import com.flatio.web.dto.FavoriteResponse;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link FavoriteMapper#isPriceChanged(BigDecimal)}, the only hand-written logic
 * on this MapStruct mapper. Field-to-field mapping is generated and not covered here per
 * testing-standards (mappers are exempt from coverage targets).
 */
class FavoriteMapperTest {

  private final FavoriteMapper mapper = new FavoriteMapper() {
    @Override
    public FavoriteResponse toResponse(Favorite favorite) {
      throw new UnsupportedOperationException("not exercised in this test");
    }
  };

  @Test
  void should_return_true_when_price_delta_is_positive() {
    // Given
    var delta = BigDecimal.valueOf(3_000);

    // When
    var result = mapper.isPriceChanged(delta);

    // Then
    assertThat(result).isTrue();
  }

  @Test
  void should_return_true_when_price_delta_is_negative() {
    // Given
    var delta = BigDecimal.valueOf(-3_000);

    // When
    var result = mapper.isPriceChanged(delta);

    // Then
    assertThat(result).isTrue();
  }

  @Test
  void should_return_false_when_price_delta_is_null() {
    // Given / When
    var result = mapper.isPriceChanged(null);

    // Then — priceAtAdd or the current listing price was null (negotiable listing)
    assertThat(result).isFalse();
  }

  @Test
  void should_return_false_when_price_delta_is_zero() {
    // Given — zero represented with a different scale than a plain ZERO constant
    var delta = new BigDecimal("0.00");

    // When
    var result = mapper.isPriceChanged(delta);

    // Then — compareTo, not equals, must be used so scale differences do not count as a change
    assertThat(result).isFalse();
  }
}
