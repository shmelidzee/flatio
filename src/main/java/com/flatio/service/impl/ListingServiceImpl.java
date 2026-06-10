package com.flatio.service.impl;

import com.flatio.common.exception.ListingNotFoundException;
import com.flatio.domain.listing.Listing;
import com.flatio.domain.listing.ListingStatus;
import com.flatio.domain.listing.PriceHistory;
import com.flatio.repository.ListingRepository;
import com.flatio.repository.PriceHistoryRepository;
import com.flatio.service.ListingService;
import com.flatio.web.dto.ListingResponse;
import com.flatio.web.dto.ListingSearchCriteria;
import com.flatio.web.dto.ListingSummaryResponse;
import com.flatio.web.dto.PriceHistoryEntry;
import com.flatio.web.mapper.ListingMapper;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@Slf4j
@RequiredArgsConstructor
public class ListingServiceImpl implements ListingService {

  @Value("${flatio.search.fts-language:russian}")
  private String ftsLanguage;

  private final ListingRepository listingRepository;
  private final PriceHistoryRepository priceHistoryRepository;
  private final ListingMapper listingMapper;

  @Override
  public ListingResponse findById(Long id) {
    Listing listing = listingRepository.findById(id)
        .orElseThrow(() -> new ListingNotFoundException(id));
    List<PriceHistory> history = priceHistoryRepository.findByListingOrderByRecordedAtDesc(listing);
    List<PriceHistoryEntry> historyEntries = history.stream()
        .map(listingMapper::toHistoryEntry)
        .toList();
    return listingMapper.toResponse(listing, historyEntries);
  }

  @Override
  public Page<ListingSummaryResponse> search(ListingSearchCriteria criteria, Pageable pageable) {
    log.debug("Searching listings with criteria={}", criteria);
    if (criteria.query() != null && !criteria.query().isBlank()) {
      return searchWithFts(criteria, pageable);
    }
    return listingRepository.findAll(buildSearchSpec(criteria), pageable)
        .map(listingMapper::toSummaryResponse);
  }

  private Page<ListingSummaryResponse> searchWithFts(ListingSearchCriteria criteria, Pageable pageable) {
    ListingStatus effectiveStatus = criteria.status() != null ? criteria.status() : ListingStatus.ACTIVE;
    String dealType = criteria.dealType() != null ? criteria.dealType().name() : null;
    String cityPattern = criteria.city() != null && !criteria.city().isBlank()
        ? "%" + criteria.city().toLowerCase() + "%" : null;
    return listingRepository.fullTextSearch(
        criteria.query(),
        ftsLanguage,
        effectiveStatus.name(),
        dealType,
        criteria.priceMin(),
        criteria.priceMax(),
        criteria.rooms(),
        cityPattern,
        criteria.sourceId(),
        criteria.propertyType(),
        pageable
    ).map(listingMapper::toSummaryResponse);
  }

  private Specification<Listing> buildSearchSpec(ListingSearchCriteria criteria) {
    return (root, query, cb) -> {
      List<Predicate> predicates = new ArrayList<>();

      // JOIN FETCH source and currency only in data query — count query must not use fetch
      if (!Long.class.equals(query.getResultType())) {
        root.fetch("source", JoinType.INNER);
        root.fetch("currency", JoinType.INNER);
        query.distinct(true);
      }

      ListingStatus effectiveStatus = criteria.status() != null ? criteria.status() : ListingStatus.ACTIVE;
      predicates.add(cb.equal(root.get("status"), effectiveStatus));

      if (criteria.dealType() != null) {
        predicates.add(cb.equal(root.get("dealType"), criteria.dealType()));
      }
      if (criteria.propertyType() != null) {
        predicates.add(cb.equal(root.get("propertyType"), criteria.propertyType()));
      }
      if (criteria.rooms() != null) {
        predicates.add(cb.equal(root.get("rooms"), criteria.rooms()));
      }
      if (criteria.priceMin() != null) {
        predicates.add(cb.greaterThanOrEqualTo(root.get("price"), criteria.priceMin()));
      }
      if (criteria.priceMax() != null) {
        predicates.add(cb.lessThanOrEqualTo(root.get("price"), criteria.priceMax()));
      }
      if (criteria.city() != null && !criteria.city().isBlank()) {
        predicates.add(cb.like(cb.lower(root.get("city")), "%" + criteria.city().toLowerCase() + "%"));
      }
      if (criteria.sourceId() != null) {
        predicates.add(cb.equal(root.get("source").get("code"), criteria.sourceId()));
      }

      return cb.and(predicates.toArray(new Predicate[0]));
    };
  }
}
