package com.flatio.service.impl;

import com.flatio.common.exception.ListingNotFoundException;
import com.flatio.domain.listing.Listing;
import com.flatio.domain.listing.ListingStatus;
import com.flatio.repository.ListingRepository;
import com.flatio.web.dto.AdminListingSearchCriteria;
import com.flatio.web.dto.ListingSummaryResponse;
import com.flatio.web.mapper.ListingMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminListingServiceImplTest {

  @Mock
  private ListingRepository listingRepository;

  @Mock
  private ListingMapper listingMapper;

  @InjectMocks
  private AdminListingServiceImpl adminListingService;

  // -------------------------------------------------------------------------
  // search
  // -------------------------------------------------------------------------

  @Test
  void should_return_page_of_listings_matching_criteria() {
    // Given
    var pageable = PageRequest.of(0, 20);
    var listing = new Listing();
    var summary = mock(ListingSummaryResponse.class);
    when(listingRepository.findAll(any(Specification.class), eq(pageable)))
        .thenReturn(new PageImpl<>(List.of(listing)));
    when(listingMapper.toSummaryResponse(listing)).thenReturn(summary);

    var criteria = new AdminListingSearchCriteria(null, null, null, null, null, null, null, null, null);

    // When
    Page<ListingSummaryResponse> result = adminListingService.search(criteria, pageable);

    // Then
    assertThat(result.getContent()).containsExactly(summary);
  }

  @Test
  void should_return_empty_page_when_no_listings_match() {
    // Given
    var pageable = PageRequest.of(0, 20);
    when(listingRepository.findAll(any(Specification.class), eq(pageable)))
        .thenReturn(new PageImpl<>(List.of()));

    var criteria = new AdminListingSearchCriteria(
        null, null, null, null, null, null, null, ListingStatus.INACTIVE, null);

    // When
    Page<ListingSummaryResponse> result = adminListingService.search(criteria, pageable);

    // Then
    assertThat(result.getContent()).isEmpty();
  }

  // -------------------------------------------------------------------------
  // updateStatus
  // -------------------------------------------------------------------------

  @Test
  void should_update_listing_status_and_return_summary() {
    // Given
    var listing = new Listing();
    listing.setId(42L);
    listing.setStatus(ListingStatus.ACTIVE);
    when(listingRepository.findById(42L)).thenReturn(Optional.of(listing));
    var summary = mock(ListingSummaryResponse.class);
    when(listingMapper.toSummaryResponse(listing)).thenReturn(summary);

    // When
    ListingSummaryResponse result = adminListingService.updateStatus(42L, ListingStatus.INACTIVE, "7");

    // Then
    assertThat(listing.getStatus()).isEqualTo(ListingStatus.INACTIVE);
    assertThat(result).isSameAs(summary);
    verify(listingRepository).save(listing);
  }

  @Test
  void should_throw_exception_when_listing_not_found_for_status_update() {
    // Given
    when(listingRepository.findById(99L)).thenReturn(Optional.empty());

    // When / Then
    assertThatThrownBy(() -> adminListingService.updateStatus(99L, ListingStatus.INACTIVE, "7"))
        .isInstanceOf(ListingNotFoundException.class)
        .hasMessageContaining("99");
  }

  // -------------------------------------------------------------------------
  // unlinkDuplicateGroup
  // -------------------------------------------------------------------------

  @Test
  void should_clear_dedup_hash_when_unlinking_duplicate_group() {
    // Given
    var listing = new Listing();
    listing.setId(5L);
    listing.setDedupHash("abc123");
    when(listingRepository.findById(5L)).thenReturn(Optional.of(listing));

    // When
    adminListingService.unlinkDuplicateGroup(5L, "7");

    // Then
    assertThat(listing.getDedupHash()).isNull();
    ArgumentCaptor<Listing> captor = ArgumentCaptor.forClass(Listing.class);
    verify(listingRepository).save(captor.capture());
    assertThat(captor.getValue().getDedupHash()).isNull();
  }

  @Test
  void should_throw_exception_when_listing_not_found_for_unlink() {
    // Given
    when(listingRepository.findById(123L)).thenReturn(Optional.empty());

    // When / Then
    assertThatThrownBy(() -> adminListingService.unlinkDuplicateGroup(123L, "7"))
        .isInstanceOf(ListingNotFoundException.class)
        .hasMessageContaining("123");
  }
}
