package com.flatio.service;

import com.flatio.common.exception.ListingNotFoundException;
import com.flatio.domain.listing.DealType;
import com.flatio.domain.listing.Listing;
import com.flatio.domain.listing.ListingStatus;
import com.flatio.domain.listing.PriceHistory;
import com.flatio.repository.ListingRepository;
import com.flatio.repository.PriceHistoryRepository;
import com.flatio.service.impl.ListingServiceImpl;
import com.flatio.web.dto.ListingResponse;
import com.flatio.web.dto.ListingSearchCriteria;
import com.flatio.web.dto.ListingSummaryResponse;
import com.flatio.web.dto.PriceHistoryEntry;
import com.flatio.web.mapper.ListingMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ListingServiceTest {

  @Mock
  private ListingRepository listingRepository;

  @Mock
  private PriceHistoryRepository priceHistoryRepository;

  @Mock
  private ListingMapper listingMapper;

  @InjectMocks
  private ListingServiceImpl listingService;

  @BeforeEach
  void setUp() {
    ReflectionTestUtils.setField(listingService, "ftsLanguage", "russian");
  }

  // -------------------------------------------------------------------------
  // findById
  // -------------------------------------------------------------------------

  @Test
  void should_return_full_response_when_listing_found() {
    // Given
    var listing = buildListing(1L);
    var historyEntry = new PriceHistoryEntry(BigDecimal.valueOf(75_000), "USD", Instant.now());
    var priceHistory = List.of(buildPriceHistory());
    var expectedResponse = buildListingResponse(1L);

    when(listingRepository.findById(1L)).thenReturn(Optional.of(listing));
    when(priceHistoryRepository.findByListingOrderByRecordedAtDesc(listing)).thenReturn(priceHistory);
    when(listingMapper.toHistoryEntry(priceHistory.get(0))).thenReturn(historyEntry);
    when(listingMapper.toResponse(eq(listing), any())).thenReturn(expectedResponse);

    // When
    var result = listingService.findById(1L);

    // Then
    assertThat(result).isNotNull();
    assertThat(result.id()).isEqualTo(1L);
    verify(listingRepository).findById(1L);
    verify(priceHistoryRepository).findByListingOrderByRecordedAtDesc(listing);
  }

  @Test
  void should_return_response_with_empty_history_when_no_price_changes() {
    // Given
    var listing = buildListing(2L);
    var expectedResponse = buildListingResponse(2L);

    when(listingRepository.findById(2L)).thenReturn(Optional.of(listing));
    when(priceHistoryRepository.findByListingOrderByRecordedAtDesc(listing)).thenReturn(Collections.emptyList());
    when(listingMapper.toResponse(eq(listing), eq(Collections.emptyList()))).thenReturn(expectedResponse);

    // When
    var result = listingService.findById(2L);

    // Then
    assertThat(result).isNotNull();
  }

  @Test
  void should_throw_exception_when_listing_not_found() {
    // Given
    when(listingRepository.findById(99L)).thenReturn(Optional.empty());

    // When / Then
    assertThatThrownBy(() -> listingService.findById(99L))
        .isInstanceOf(ListingNotFoundException.class)
        .hasMessageContaining("99");
  }

  // -------------------------------------------------------------------------
  // search
  // -------------------------------------------------------------------------

  @Test
  void should_return_page_of_summaries_when_listings_exist() {
    // Given
    var pageable = PageRequest.of(0, 20);
    var listing = buildListing(1L);
    var summary = buildListingSummary(1L);
    var page = new PageImpl<>(List.of(listing), pageable, 1);

    when(listingRepository.findAll(any(Specification.class), eq(pageable))).thenReturn(page);
    when(listingMapper.toSummaryResponse(listing)).thenReturn(summary);

    var criteria = new ListingSearchCriteria(null, null, null, null, null, null, null, null, null, null);

    // When
    var result = listingService.search(criteria, pageable);

    // Then
    assertThat(result).isNotNull();
    assertThat(result.getTotalElements()).isEqualTo(1);
    assertThat(result.getContent()).hasSize(1);
  }

  @Test
  void should_return_empty_page_when_no_listings_match() {
    // Given
    var pageable = PageRequest.of(0, 20);
    Page<Listing> emptyPage = new PageImpl<>(Collections.emptyList(), pageable, 0);

    when(listingRepository.findAll(any(Specification.class), eq(pageable))).thenReturn(emptyPage);

    var criteria = new ListingSearchCriteria(DealType.SELL, null, null, "Гомель", null, null, null, null, null, null);

    // When
    var result = listingService.search(criteria, pageable);

    // Then
    assertThat(result.getTotalElements()).isZero();
    assertThat(result.getContent()).isEmpty();
  }

  @Test
  void should_apply_specification_when_criteria_has_filters() {
    // Given
    var pageable = PageRequest.of(0, 10);
    Page<Listing> emptyPage = new PageImpl<>(Collections.emptyList(), pageable, 0);

    when(listingRepository.findAll(any(Specification.class), eq(pageable))).thenReturn(emptyPage);

    var criteria = new ListingSearchCriteria(
        DealType.RENT, "APARTMENT", "onliner", "Минск",
        BigDecimal.valueOf(500), BigDecimal.valueOf(1500), 2, ListingStatus.ACTIVE, null, null
    );

    // When
    listingService.search(criteria, pageable);

    // Then — Specification was passed to repository (its building/execution is tested via integration tests)
    verify(listingRepository).findAll(any(Specification.class), eq(pageable));
  }

  @Test
  void should_pass_createdAt_desc_sort_to_repository_when_no_query() {
    // Given — Pageable with createdAt DESC sort (matches REST API default after fix)
    var pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"));
    Page<Listing> emptyPage = new PageImpl<>(Collections.emptyList(), pageable, 0);

    var pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
    when(listingRepository.findAll(any(Specification.class), pageableCaptor.capture()))
        .thenReturn(emptyPage);

    var criteria = new ListingSearchCriteria(null, null, null, null, null, null, null, null, null, null);

    // When
    listingService.search(criteria, pageable);

    // Then — repository receives the same createdAt DESC sort unchanged
    Sort.Order capturedOrder = pageableCaptor.getValue().getSort().getOrderFor("createdAt");
    assertThat(capturedOrder).isNotNull();
    assertThat(capturedOrder.getDirection()).isEqualTo(Sort.Direction.DESC);
  }

  @Test
  void should_preserve_sort_order_across_pages_when_paginating() {
    // Given — page 1 and page 2 both carry createdAt DESC sort
    var pageOne = PageRequest.of(0, 5, Sort.by(Sort.Direction.DESC, "createdAt"));
    var pageTwo = PageRequest.of(1, 5, Sort.by(Sort.Direction.DESC, "createdAt"));
    var listing = buildListing(1L);
    var page = new PageImpl<>(List.of(listing), pageOne, 10);

    when(listingRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);
    when(listingMapper.toSummaryResponse(listing)).thenReturn(buildListingSummary(1L));

    var criteria = new ListingSearchCriteria(null, null, null, null, null, null, null, null, null, null);
    var pageableCaptor = ArgumentCaptor.forClass(Pageable.class);

    // When — call search twice simulating pagination
    listingService.search(criteria, pageOne);
    listingService.search(criteria, pageTwo);

    // Then — both calls pass createdAt DESC sort to repository
    verify(listingRepository, times(2))
        .findAll(any(Specification.class), pageableCaptor.capture());

    pageableCaptor.getAllValues().forEach(p -> {
      Sort.Order order = p.getSort().getOrderFor("createdAt");
      assertThat(order).isNotNull();
      assertThat(order.getDirection()).isEqualTo(Sort.Direction.DESC);
    });
  }

  // -------------------------------------------------------------------------
  // search — FTS routing
  // -------------------------------------------------------------------------

  @Test
  void should_call_fullTextSearch_when_query_is_present() {
    // Given
    var pageable = PageRequest.of(0, 20);
    var listing = buildListing(1L);
    var summary = buildListingSummary(1L);
    var page = new PageImpl<>(List.of(listing), pageable, 1);

    when(listingRepository.fullTextSearch(
        eq("двухкомнатная квартира"),
        eq("russian"),
        eq("ACTIVE"),
        isNull(),
        isNull(),
        isNull(),
        isNull(),
        isNull(),
        isNull(),
        isNull(),
        isNull(),
        eq(pageable)
    )).thenReturn(page);
    when(listingMapper.toSummaryResponse(listing)).thenReturn(summary);

    var criteria = new ListingSearchCriteria(null, null, null, null, null, null, null, null, "двухкомнатная квартира", null);

    // When
    var result = listingService.search(criteria, pageable);

    // Then
    assertThat(result.getTotalElements()).isEqualTo(1);
    verify(listingRepository).fullTextSearch(
        eq("двухкомнатная квартира"),
        eq("russian"),
        eq("ACTIVE"),
        isNull(),
        isNull(),
        isNull(),
        isNull(),
        isNull(),
        isNull(),
        isNull(),
        isNull(),
        eq(pageable)
    );
    verify(listingRepository, never()).findAll(any(Specification.class), eq(pageable));
  }

  @Test
  void should_not_call_fullTextSearch_when_query_is_null() {
    // Given
    var pageable = PageRequest.of(0, 20);
    Page<Listing> emptyPage = new PageImpl<>(Collections.emptyList(), pageable, 0);

    when(listingRepository.findAll(any(Specification.class), eq(pageable))).thenReturn(emptyPage);

    var criteria = new ListingSearchCriteria(null, null, null, null, null, null, null, null, null, null);

    // When
    listingService.search(criteria, pageable);

    // Then
    verify(listingRepository).findAll(any(Specification.class), eq(pageable));
    verify(listingRepository, never()).fullTextSearch(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void should_not_call_fullTextSearch_when_query_is_blank() {
    // Given
    var pageable = PageRequest.of(0, 20);
    Page<Listing> emptyPage = new PageImpl<>(Collections.emptyList(), pageable, 0);

    when(listingRepository.findAll(any(Specification.class), eq(pageable))).thenReturn(emptyPage);

    var criteria = new ListingSearchCriteria(null, null, null, null, null, null, null, null, "   ", null);

    // When
    listingService.search(criteria, pageable);

    // Then
    verify(listingRepository).findAll(any(Specification.class), eq(pageable));
    verify(listingRepository, never()).fullTextSearch(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void should_pass_status_as_string_when_routing_to_fts() {
    // Given
    var pageable = PageRequest.of(0, 20);
    Page<Listing> emptyPage = new PageImpl<>(Collections.emptyList(), pageable, 0);

    when(listingRepository.fullTextSearch(
        any(), any(), eq("ACTIVE"), any(), any(), any(), any(), any(), any(), any(), any(), any()
    )).thenReturn(emptyPage);

    // criteria.status() == null → effectiveStatus должен стать ACTIVE
    var criteria = new ListingSearchCriteria(null, null, null, null, null, null, null, null, "квартира", null);

    // When
    listingService.search(criteria, pageable);

    // Then
    verify(listingRepository).fullTextSearch(
        any(), any(), eq("ACTIVE"), any(), any(), any(), any(), any(), any(), any(), any(), any()
    );
  }

  @Test
  void should_pass_all_structural_filters_to_fts_query() {
    // Given
    var pageable = PageRequest.of(0, 20);
    Page<Listing> emptyPage = new PageImpl<>(Collections.emptyList(), pageable, 0);

    when(listingRepository.fullTextSearch(
        eq("квартира"),
        eq("russian"),
        eq("ACTIVE"),
        eq("RENT"),
        eq(BigDecimal.valueOf(500)),
        eq(BigDecimal.valueOf(1500)),
        eq(2),
        eq("%минск%"),
        eq("onliner"),
        eq("APARTMENT"),
        isNull(),
        eq(pageable)
    )).thenReturn(emptyPage);

    var criteria = new ListingSearchCriteria(
        DealType.RENT, "APARTMENT", "onliner", "Минск",
        BigDecimal.valueOf(500), BigDecimal.valueOf(1500), 2, null, "квартира", null
    );

    // When
    listingService.search(criteria, pageable);

    // Then
    verify(listingRepository).fullTextSearch(
        eq("квартира"),
        eq("russian"),
        eq("ACTIVE"),
        eq("RENT"),
        eq(BigDecimal.valueOf(500)),
        eq(BigDecimal.valueOf(1500)),
        eq(2),
        eq("%минск%"),
        eq("onliner"),
        eq("APARTMENT"),
        isNull(),
        eq(pageable)
    );
  }

  @Test
  void should_pass_owner_only_true_to_fts_when_set_in_criteria() {
    // Given — ownerOnly filter must be forwarded to FTS query (#147)
    var pageable = PageRequest.of(0, 20);
    Page<Listing> emptyPage = new PageImpl<>(Collections.emptyList(), pageable, 0);

    when(listingRepository.fullTextSearch(
        any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), eq(Boolean.TRUE), any()
    )).thenReturn(emptyPage);

    var criteria = new ListingSearchCriteria(null, null, null, null, null, null, null, null, "квартира", Boolean.TRUE);

    // When
    listingService.search(criteria, pageable);

    // Then — ownerOnly=true passed as non-null to fullTextSearch
    verify(listingRepository).fullTextSearch(
        any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), eq(Boolean.TRUE), any()
    );
  }

  // -------------------------------------------------------------------------
  // toNativePageable — sort field conversion and injection guard
  // -------------------------------------------------------------------------

  @Test
  void should_convert_camel_case_sort_to_snake_case_for_fts_native_query() {
    // Given — sort by Java field name "createdAt" (camelCase)
    var pageableWithCamelSort = PageRequest.of(0, 5, Sort.by(Sort.Direction.DESC, "createdAt"));
    Page<Listing> emptyPage = Page.empty();
    when(listingRepository.fullTextSearch(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(emptyPage);

    var criteria = new ListingSearchCriteria(null, null, null, null, null, null, null, null, "квартира", null);

    // When
    listingService.search(criteria, pageableWithCamelSort);

    // Then — fullTextSearch receives snake_case "created_at", not camelCase "createdAt"
    var pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
    verify(listingRepository).fullTextSearch(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), pageableCaptor.capture());

    Sort.Order capturedOrder = pageableCaptor.getValue().getSort().getOrderFor("created_at");
    assertThat(capturedOrder).isNotNull();
    assertThat(capturedOrder.getDirection()).isEqualTo(Sort.Direction.DESC);
  }

  @Test
  void should_throw_when_fts_sort_field_is_not_in_allowed_list() {
    // Given — attempt to sort by a field not in the whitelist
    var maliciousPageable = PageRequest.of(0, 5, Sort.by("title;DROP TABLE listings;--"));
    var criteria = new ListingSearchCriteria(null, null, null, null, null, null, null, null, "квартира", null);

    // When / Then — rejected before reaching the repository
    assertThatThrownBy(() -> listingService.search(criteria, maliciousPageable))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not allowed");
  }

  @Test
  void should_pass_null_prices_and_owner_only_to_fts_when_keyword_with_partial_filters() {
    // Given — exact criteria combination from issue #163 (42P18 repro)
    var pageable = PageRequest.of(0, 20);
    Page<Listing> emptyPage = new PageImpl<>(Collections.emptyList(), pageable, 0);

    when(listingRepository.fullTextSearch(
        eq("Копище"),
        eq("russian"),
        eq("ACTIVE"),
        eq("RENT"),
        isNull(),
        isNull(),
        eq(2),
        isNull(),
        isNull(),
        eq("APARTMENT"),
        eq(Boolean.TRUE),
        eq(pageable)
    )).thenReturn(emptyPage);

    var criteria = new ListingSearchCriteria(
        DealType.RENT, "APARTMENT", null, null,
        null, null, 2, null, "Копище", Boolean.TRUE
    );

    // When
    listingService.search(criteria, pageable);

    // Then — null priceMin/priceMax and ownerOnly=true correctly forwarded
    verify(listingRepository).fullTextSearch(
        eq("Копище"),
        eq("russian"),
        eq("ACTIVE"),
        eq("RENT"),
        isNull(),
        isNull(),
        eq(2),
        isNull(),
        isNull(),
        eq("APARTMENT"),
        eq(Boolean.TRUE),
        eq(pageable)
    );
  }

  @Test
  void should_convert_city_to_like_pattern_when_routing_to_fts() {
    // Given
    var pageable = PageRequest.of(0, 20);
    Page<Listing> emptyPage = new PageImpl<>(Collections.emptyList(), pageable, 0);

    when(listingRepository.fullTextSearch(
        any(), any(), any(), any(), any(), any(), any(), eq("%минск%"), any(), any(), any(), any()
    )).thenReturn(emptyPage);

    var criteria = new ListingSearchCriteria(null, null, null, "Минск", null, null, null, null, "квартира", null);

    // When
    listingService.search(criteria, pageable);

    // Then
    verify(listingRepository).fullTextSearch(
        any(), any(), any(), any(), any(), any(), any(), eq("%минск%"), any(), any(), any(), any()
    );
  }

  // -------------------------------------------------------------------------
  // helpers
  // -------------------------------------------------------------------------

  private static Listing buildListing(Long id) {
    var listing = new Listing();
    listing.setId(id);
    listing.setTitle("Test listing");
    listing.setPrice(BigDecimal.valueOf(75_000));
    listing.setStatus(ListingStatus.ACTIVE);
    listing.setDealType(DealType.SELL);
    listing.setSourceUrl("https://example.com/listing/" + id);
    return listing;
  }

  private static PriceHistory buildPriceHistory() {
    var ph = new PriceHistory();
    ph.setPrice(BigDecimal.valueOf(75_000));
    ph.setRecordedAt(Instant.now());
    return ph;
  }

  private static ListingResponse buildListingResponse(Long id) {
    return new ListingResponse(
        id, "ext-1", "realt", "Test listing", null, DealType.SELL, null, "APARTMENT",
        BigDecimal.valueOf(75_000), "USD", 2, 5, 9,
        BigDecimal.valueOf(52.5), "ул. Ленина, 1", "Минск", null, null, null,
        true, ListingStatus.ACTIVE, "https://realt.by/1", Instant.now(), Instant.now(), List.of()
    );
  }

  private static ListingSummaryResponse buildListingSummary(Long id) {
    return new ListingSummaryResponse(
        id, "Test listing", BigDecimal.valueOf(75_000), "USD", null, 2,
        null, BigDecimal.valueOf(52.5), "Минск", null, "realt", Instant.now(), null,
        "https://realt.by/" + id
    );
  }
}
