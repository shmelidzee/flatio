package com.flatio.service;

import com.flatio.common.exception.ListingNotFoundException;
import com.flatio.domain.currency.Currency;
import com.flatio.domain.listing.DealType;
import com.flatio.domain.listing.Listing;
import com.flatio.domain.listing.ListingStatus;
import com.flatio.domain.listing.PriceHistory;
import com.flatio.domain.source.Source;
import com.flatio.repository.CurrencyRepository;
import com.flatio.repository.ListingRepository;
import com.flatio.repository.PriceHistoryRepository;
import com.flatio.repository.SourceRepository;
import com.flatio.service.CurrencyRateService;
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
import java.util.Set;
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
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
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
  private SourceRepository sourceRepository;

  @Mock
  private CurrencyRepository currencyRepository;

  @Mock
  private ListingMapper listingMapper;

  @Mock
  private CurrencyRateService currencyRateService;

  @InjectMocks
  private ListingServiceImpl listingService;

  @BeforeEach
  void setUp() {
    ReflectionTestUtils.setField(listingService, "ftsLanguage", "russian");
    // Default: NBRB rate unavailable — no enrichment; tests that need enrichment override this.
    // lenient() prevents UnnecessaryStubbingException in findById tests that never call search().
    lenient().when(currencyRateService.getUsdToByn()).thenReturn(Optional.empty());
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
    when(listingMapper.toResponse(eq(listing), any(), eq(false))).thenReturn(expectedResponse);

    // When
    var result = listingService.findById(1L);

    // Then
    assertThat(result).isNotNull();
    assertThat(result.id()).isEqualTo(1L);
    verify(listingRepository).findById(1L);
    verify(priceHistoryRepository).findByListingOrderByRecordedAtDesc(listing);
    verify(listingRepository, never()).existsByDedupHashAndIdNot(any(), any());
  }

  @Test
  void should_return_response_with_empty_history_when_no_price_changes() {
    // Given
    var listing = buildListing(2L);
    var expectedResponse = buildListingResponse(2L);

    when(listingRepository.findById(2L)).thenReturn(Optional.of(listing));
    when(priceHistoryRepository.findByListingOrderByRecordedAtDesc(listing)).thenReturn(Collections.emptyList());
    when(listingMapper.toResponse(eq(listing), eq(Collections.emptyList()), eq(false))).thenReturn(expectedResponse);

    // When
    var result = listingService.findById(2L);

    // Then
    assertThat(result).isNotNull();
  }

  @Test
  void should_pass_has_duplicates_true_when_another_listing_shares_dedup_hash() {
    // Given
    var listing = buildListing(3L);
    listing.setDedupHash("hash-abc");
    var expectedResponse = buildListingResponse(3L);

    when(listingRepository.findById(3L)).thenReturn(Optional.of(listing));
    when(priceHistoryRepository.findByListingOrderByRecordedAtDesc(listing)).thenReturn(Collections.emptyList());
    when(listingRepository.existsByDedupHashAndIdNot("hash-abc", 3L)).thenReturn(true);
    when(listingMapper.toResponse(eq(listing), eq(Collections.emptyList()), eq(true))).thenReturn(expectedResponse);

    // When
    var result = listingService.findById(3L);

    // Then
    assertThat(result).isNotNull();
    verify(listingRepository).existsByDedupHashAndIdNot("hash-abc", 3L);
  }

  @Test
  void should_skip_duplicate_check_when_dedup_hash_is_null() {
    // Given
    var listing = buildListing(4L);
    var expectedResponse = buildListingResponse(4L);

    when(listingRepository.findById(4L)).thenReturn(Optional.of(listing));
    when(priceHistoryRepository.findByListingOrderByRecordedAtDesc(listing)).thenReturn(Collections.emptyList());
    when(listingMapper.toResponse(eq(listing), eq(Collections.emptyList()), eq(false))).thenReturn(expectedResponse);

    // When
    listingService.findById(4L);

    // Then
    verify(listingRepository, never()).existsByDedupHashAndIdNot(any(), any());
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

    var criteria = new ListingSearchCriteria(null, null, null, null, null, null, null, null, null, null, null);

    // When
    var result = listingService.search(criteria, pageable, null);

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

    var criteria = new ListingSearchCriteria(DealType.SELL, null, null, "Гомель", null, null, null, null, null, null, null);

    // When
    var result = listingService.search(criteria, pageable, null);

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
        null, BigDecimal.valueOf(500), BigDecimal.valueOf(1500), 2, ListingStatus.ACTIVE, null, null
    );

    // When
    listingService.search(criteria, pageable, null);

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

    var criteria = new ListingSearchCriteria(null, null, null, null, null, null, null, null, null, null, null);

    // When
    listingService.search(criteria, pageable, null);

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

    var criteria = new ListingSearchCriteria(null, null, null, null, null, null, null, null, null, null, null);
    var pageableCaptor = ArgumentCaptor.forClass(Pageable.class);

    // When — call search twice simulating pagination
    listingService.search(criteria, pageOne, null);
    listingService.search(criteria, pageTwo, null);

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
  // search — blacklist exclusion userId propagation (issue #414)
  // -------------------------------------------------------------------------

  @Test
  void should_pass_authenticated_user_id_to_fts_query_for_blacklist_exclusion() {
    // Given
    var pageable = PageRequest.of(0, 20);
    Page<Listing> emptyPage = new PageImpl<>(Collections.emptyList(), pageable, 0);

    when(listingRepository.fullTextSearch(
        any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), eq(7L), any()
    )).thenReturn(emptyPage);

    var criteria = new ListingSearchCriteria(null, null, null, null, null, null, null, null, null, "квартира", null);

    // When
    listingService.search(criteria, pageable, 7L);

    // Then
    verify(listingRepository).fullTextSearch(
        any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), eq(7L), any()
    );
  }

  @Test
  void should_not_throw_when_user_id_is_null_for_default_search() {
    // Given — anonymous/user-less caller must not break the Specification-based search path
    var pageable = PageRequest.of(0, 10);
    Page<Listing> emptyPage = new PageImpl<>(Collections.emptyList(), pageable, 0);
    when(listingRepository.findAll(any(Specification.class), eq(pageable))).thenReturn(emptyPage);
    var criteria = new ListingSearchCriteria(null, null, null, null, null, null, null, null, null, null, null);

    // When / Then
    assertThatNoException().isThrownBy(() -> listingService.search(criteria, pageable, null));
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
        isNull(),
        eq(pageable)
    )).thenReturn(page);
    when(listingMapper.toSummaryResponse(listing)).thenReturn(summary);

    var criteria = new ListingSearchCriteria(null, null, null, null, null, null, null, null, null, "двухкомнатная квартира", null);

    // When
    var result = listingService.search(criteria, pageable, null);

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
        isNull(),
        eq(pageable)
    );
    verify(listingRepository, never()).findAll(any(Specification.class), eq(pageable));
  }

  @Test
  void should_batch_load_distinct_sources_and_currencies_for_fts_page() {
    // Given — two listings from the same source/currency in one FTS page (issue #377: the
    // native query cannot JOIN FETCH, so source/currency are primed via a batch lookup instead
    // of one query per row)
    var pageable = PageRequest.of(0, 20);
    var listing1 = buildListingWithSourceAndCurrency(1L, 10L, 20L);
    var listing2 = buildListingWithSourceAndCurrency(2L, 10L, 20L);
    var page = new PageImpl<>(List.of(listing1, listing2), pageable, 2);

    when(listingRepository.fullTextSearch(
        eq("квартира"), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()
    )).thenReturn(page);
    when(listingMapper.toSummaryResponse(any())).thenReturn(buildListingSummary(1L));

    var criteria = new ListingSearchCriteria(null, null, null, null, null, null, null, null, null, "квартира", null);

    // When
    listingService.search(criteria, pageable, null);

    // Then — one batch lookup per distinct id set, not one per row
    verify(sourceRepository).findAllById(Set.of(10L));
    verify(currencyRepository).findAllById(Set.of(20L));
  }

  @Test
  void should_not_call_fullTextSearch_when_query_is_null() {
    // Given
    var pageable = PageRequest.of(0, 20);
    Page<Listing> emptyPage = new PageImpl<>(Collections.emptyList(), pageable, 0);

    when(listingRepository.findAll(any(Specification.class), eq(pageable))).thenReturn(emptyPage);

    var criteria = new ListingSearchCriteria(null, null, null, null, null, null, null, null, null, null, null);

    // When
    listingService.search(criteria, pageable, null);

    // Then
    verify(listingRepository).findAll(any(Specification.class), eq(pageable));
    verify(listingRepository, never()).fullTextSearch(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void should_not_call_fullTextSearch_when_query_is_blank() {
    // Given
    var pageable = PageRequest.of(0, 20);
    Page<Listing> emptyPage = new PageImpl<>(Collections.emptyList(), pageable, 0);

    when(listingRepository.findAll(any(Specification.class), eq(pageable))).thenReturn(emptyPage);

    var criteria = new ListingSearchCriteria(null, null, null, null, null, null, null, null, null, "   ", null);

    // When
    listingService.search(criteria, pageable, null);

    // Then
    verify(listingRepository).findAll(any(Specification.class), eq(pageable));
    verify(listingRepository, never()).fullTextSearch(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void should_pass_status_as_string_when_routing_to_fts() {
    // Given
    var pageable = PageRequest.of(0, 20);
    Page<Listing> emptyPage = new PageImpl<>(Collections.emptyList(), pageable, 0);

    when(listingRepository.fullTextSearch(
        any(), any(), eq("ACTIVE"), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()
    )).thenReturn(emptyPage);

    // criteria.status() == null → effectiveStatus должен стать ACTIVE
    var criteria = new ListingSearchCriteria(null, null, null, null, null, null, null, null, null, "квартира", null);

    // When
    listingService.search(criteria, pageable, null);

    // Then
    verify(listingRepository).fullTextSearch(
        any(), any(), eq("ACTIVE"), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()
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
        isNull(),
        eq(pageable)
    )).thenReturn(emptyPage);

    var criteria = new ListingSearchCriteria(
        DealType.RENT, "APARTMENT", "onliner", "Минск",
        null, BigDecimal.valueOf(500), BigDecimal.valueOf(1500), 2, null, "квартира", null
    );

    // When
    listingService.search(criteria, pageable, null);

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
        any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), eq(Boolean.TRUE), any(), any()
    )).thenReturn(emptyPage);

    var criteria = new ListingSearchCriteria(null, null, null, null, null, null, null, null, null, "квартира", Boolean.TRUE);

    // When
    listingService.search(criteria, pageable, null);

    // Then — ownerOnly=true passed as non-null to fullTextSearch
    verify(listingRepository).fullTextSearch(
        any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), eq(Boolean.TRUE), any(), any()
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
    when(listingRepository.fullTextSearch(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(emptyPage);

    var criteria = new ListingSearchCriteria(null, null, null, null, null, null, null, null, null, "квартира", null);

    // When
    listingService.search(criteria, pageableWithCamelSort, null);

    // Then — fullTextSearch receives snake_case "created_at", not camelCase "createdAt"
    var pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
    verify(listingRepository).fullTextSearch(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), pageableCaptor.capture());

    Sort.Order capturedOrder = pageableCaptor.getValue().getSort().getOrderFor("created_at");
    assertThat(capturedOrder).isNotNull();
    assertThat(capturedOrder.getDirection()).isEqualTo(Sort.Direction.DESC);
  }

  @Test
  void should_throw_when_fts_sort_field_is_not_in_allowed_list() {
    // Given — attempt to sort by a field not in the whitelist
    var maliciousPageable = PageRequest.of(0, 5, Sort.by("title;DROP TABLE listings;--"));
    var criteria = new ListingSearchCriteria(null, null, null, null, null, null, null, null, null, "квартира", null);

    // When / Then — rejected before reaching the repository
    assertThatThrownBy(() -> listingService.search(criteria, maliciousPageable, null))
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
        isNull(),
        eq(pageable)
    )).thenReturn(emptyPage);

    var criteria = new ListingSearchCriteria(
        DealType.RENT, "APARTMENT", null, null,
        null, null, null, 2, null, "Копище", Boolean.TRUE
    );

    // When
    listingService.search(criteria, pageable, null);

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
        isNull(),
        eq(pageable)
    );
  }

  @Test
  void should_route_city_keyword_to_fts_with_null_city_pattern_when_city_embedded_in_query() {
    // Given — city name typed as keyword (V30 design: city column is part of search_vector)
    // Issue #304: city embedded in keyword is matched via FTS, not via a separate LIKE filter
    var pageable = PageRequest.of(0, 20);
    Page<Listing> emptyPage = new PageImpl<>(Collections.emptyList(), pageable, 0);

    when(listingRepository.fullTextSearch(
        eq("Минск тихий двор"),
        eq("russian"),
        eq("ACTIVE"),
        isNull(), isNull(), isNull(), isNull(),
        isNull(),       // cityPattern null — city is matched via search_vector, not LIKE
        isNull(), isNull(), isNull(),
        isNull(),
        eq(pageable)
    )).thenReturn(emptyPage);

    // criteria.city() = null (Telegram always sends null city; city name is in the keyword string)
    var criteria = new ListingSearchCriteria(null, null, null, null, null, null, null, null, null, "Минск тихий двор", null);

    // When
    listingService.search(criteria, pageable, null);

    // Then — FTS path used (query non-null); cityPattern null because no separate city filter
    verify(listingRepository).fullTextSearch(
        eq("Минск тихий двор"),
        eq("russian"),
        eq("ACTIVE"),
        isNull(), isNull(), isNull(), isNull(),
        isNull(),
        isNull(), isNull(), isNull(),
        isNull(),
        eq(pageable)
    );
    verify(listingRepository, never()).findAll(any(Specification.class), eq(pageable));
  }

  @Test
  void should_convert_city_to_like_pattern_when_routing_to_fts() {
    // Given
    var pageable = PageRequest.of(0, 20);
    Page<Listing> emptyPage = new PageImpl<>(Collections.emptyList(), pageable, 0);

    when(listingRepository.fullTextSearch(
        any(), any(), any(), any(), any(), any(), any(), eq("%минск%"), any(), any(), any(), any(), any()
    )).thenReturn(emptyPage);

    var criteria = new ListingSearchCriteria(null, null, null, "Минск", null, null, null, null, null, "квартира", null);

    // When
    listingService.search(criteria, pageable, null);

    // Then
    verify(listingRepository).fullTextSearch(
        any(), any(), any(), any(), any(), any(), any(), eq("%минск%"), any(), any(), any(), any(), any()
    );
  }

  // -------------------------------------------------------------------------
  // search — BYN price enrichment with NBRB rate (#257)
  // -------------------------------------------------------------------------

  @Test
  void should_enrich_byn_listing_with_usd_equivalent_when_nbrb_rate_available() {
    // Given — Onliner listing: price in BYN, priceUsd not set in entity
    var pageable = PageRequest.of(0, 20);
    var listing = buildListing(1L);
    var summaryByn = new ListingSummaryResponse(
        1L, "Test", BigDecimal.valueOf(3_387.36), "BYN",
        null, null, 1, null, null, "Минск", null, null,
        "onliner", Instant.now(), null, "https://onliner.by/1", null
    );
    var page = new PageImpl<>(List.of(listing), pageable, 1);

    when(listingRepository.findAll(any(Specification.class), eq(pageable))).thenReturn(page);
    when(listingMapper.toSummaryResponse(listing)).thenReturn(summaryByn);
    when(currencyRateService.getUsdToByn()).thenReturn(Optional.of(BigDecimal.valueOf(2.8228)));

    var criteria = new ListingSearchCriteria(null, null, null, null, null, null, null, null, null, null, null);

    // When
    var result = listingService.search(criteria, pageable, null);

    // Then — priceUsd is derived from BYN / rate; currency and price remain unchanged
    var enriched = result.getContent().get(0);
    assertThat(enriched.currency()).isEqualTo("BYN");
    assertThat(enriched.price()).isEqualByComparingTo(BigDecimal.valueOf(3_387.36));
    assertThat(enriched.priceUsd()).isNotNull();
    assertThat(enriched.priceUsd()).isGreaterThan(BigDecimal.ZERO);
  }

  @Test
  void should_not_enrich_byn_listing_when_nbrb_rate_unavailable() {
    // Given — NBRB unavailable; BYN listing must be returned as-is without priceUsd
    var pageable = PageRequest.of(0, 20);
    var listing = buildListing(1L);
    var summaryByn = new ListingSummaryResponse(
        1L, "Test", BigDecimal.valueOf(1_000), "BYN",
        null, null, 1, null, null, "Минск", null, null,
        "onliner", Instant.now(), null, "https://onliner.by/1", null
    );
    var page = new PageImpl<>(List.of(listing), pageable, 1);

    when(listingRepository.findAll(any(Specification.class), eq(pageable))).thenReturn(page);
    when(listingMapper.toSummaryResponse(listing)).thenReturn(summaryByn);
    // currencyRateService.getUsdToByn() returns empty by default (set in setUp)

    var criteria = new ListingSearchCriteria(null, null, null, null, null, null, null, null, null, null, null);

    // When
    var result = listingService.search(criteria, pageable, null);

    // Then — priceUsd remains null; no enrichment without rate
    assertThat(result.getContent().get(0).priceUsd()).isNull();
  }

  @Test
  void should_not_enrich_when_price_usd_already_set() {
    // Given — Realt listing: priceUsd already set from source; must not be overwritten
    var pageable = PageRequest.of(0, 20);
    var listing = buildListing(1L);
    var realtSummary = new ListingSummaryResponse(
        1L, "Test", BigDecimal.valueOf(1_200), "USD",
        BigDecimal.valueOf(1_200), BigDecimal.valueOf(3_387.36), 2, null, null, "Минск", null, null,
        "realt", Instant.now(), null, "https://realt.by/1", null
    );
    var page = new PageImpl<>(List.of(listing), pageable, 1);

    when(listingRepository.findAll(any(Specification.class), eq(pageable))).thenReturn(page);
    when(listingMapper.toSummaryResponse(listing)).thenReturn(realtSummary);
    when(currencyRateService.getUsdToByn()).thenReturn(Optional.of(BigDecimal.valueOf(3.3228)));

    var criteria = new ListingSearchCriteria(null, null, null, null, null, null, null, null, null, null, null);

    // When
    var result = listingService.search(criteria, pageable, null);

    // Then — priceUsd stays as set by source (1200), not recomputed
    assertThat(result.getContent().get(0).priceUsd()).isEqualByComparingTo(BigDecimal.valueOf(1_200));
  }

  @Test
  void should_not_enrich_when_currency_is_not_byn() {
    // Given — USD-priced listing without priceUsd yet (edge case), rate available; must be skipped
    var pageable = PageRequest.of(0, 20);
    var listing = buildListing(1L);
    var usdSummary = new ListingSummaryResponse(
        1L, "Test", BigDecimal.valueOf(500), "USD",
        null, null, 1, null, null, "Минск", null, null,
        "onliner", Instant.now(), null, "https://onliner.by/1", null
    );
    var page = new PageImpl<>(List.of(listing), pageable, 1);

    when(listingRepository.findAll(any(Specification.class), eq(pageable))).thenReturn(page);
    when(listingMapper.toSummaryResponse(listing)).thenReturn(usdSummary);
    when(currencyRateService.getUsdToByn()).thenReturn(Optional.of(BigDecimal.valueOf(3.3228)));

    var criteria = new ListingSearchCriteria(null, null, null, null, null, null, null, null, null, null, null);

    // When
    var result = listingService.search(criteria, pageable, null);

    // Then — non-BYN listings are not touched by enrichment
    assertThat(result.getContent().get(0).priceUsd()).isNull();
  }

  @Test
  void should_enrich_byn_listing_via_fts_path_when_rate_available() {
    // Given — FTS search path: query is present, listing is BYN-priced
    var pageable = PageRequest.of(0, 20);
    var listing = buildListing(1L);
    var summaryByn = new ListingSummaryResponse(
        1L, "Test", BigDecimal.valueOf(3_322.80), "BYN",
        null, null, 1, null, null, "Минск", null, null,
        "onliner", Instant.now(), null, "https://onliner.by/1", null
    );
    var page = new PageImpl<>(List.of(listing), pageable, 1);

    when(listingRepository.fullTextSearch(
        eq("квартира"), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()
    )).thenReturn(page);
    when(listingMapper.toSummaryResponse(listing)).thenReturn(summaryByn);
    when(currencyRateService.getUsdToByn()).thenReturn(Optional.of(BigDecimal.valueOf(3.3228)));

    var criteria = new ListingSearchCriteria(null, null, null, null, null, null, null, null, null, "квартира", null);

    // When
    var result = listingService.search(criteria, pageable, null);

    // Then — FTS path also enriches priceUsd from BYN rate
    assertThat(result.getContent().get(0).priceUsd()).isNotNull();
    assertThat(result.getContent().get(0).priceUsd()).isGreaterThan(BigDecimal.ZERO);
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

  private static Listing buildListingWithSourceAndCurrency(Long id, Long sourceId, Long currencyId) {
    var listing = buildListing(id);
    var source = new Source();
    source.setId(sourceId);
    var currency = new Currency();
    currency.setId(currencyId);
    listing.setSource(source);
    listing.setCurrency(currency);
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
        BigDecimal.valueOf(75_000), null, "USD", 2, 5, 9,
        BigDecimal.valueOf(52.5), "ул. Ленина, 1", "Минск", null, null, null,
        true, null, ListingStatus.ACTIVE, "https://realt.by/1", Instant.now(), Instant.now(), List.of(), false
    );
  }

  private static ListingSummaryResponse buildListingSummary(Long id) {
    return new ListingSummaryResponse(
        id, "Test listing", BigDecimal.valueOf(75_000), "USD", null, null, 2,
        null, BigDecimal.valueOf(52.5), "Минск", null, null, "realt", Instant.now(), null,
        "https://realt.by/" + id, null
    );
  }
}
