package com.flatio.service.impl;

import com.flatio.common.exception.ListingConcurrentModificationException;
import com.flatio.common.exception.ListingNotFoundException;
import com.flatio.common.util.LikePatternUtils;
import com.flatio.domain.audit.AdminAuditObjectType;
import com.flatio.domain.listing.Listing;
import com.flatio.domain.listing.ListingStatus;
import com.flatio.repository.ListingRepository;
import com.flatio.service.AdminAuditLogService;
import com.flatio.service.AdminListingService;
import com.flatio.web.dto.AdminListingSearchCriteria;
import com.flatio.web.dto.ListingSummaryResponse;
import com.flatio.web.mapper.ListingMapper;
import jakarta.persistence.criteria.CommonAbstractCriteria;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@Slf4j
@RequiredArgsConstructor
public class AdminListingServiceImpl implements AdminListingService {

  private final ListingRepository listingRepository;
  private final ListingMapper listingMapper;
  private final AdminAuditLogService adminAuditLogService;

  @Override
  public Page<ListingSummaryResponse> search(AdminListingSearchCriteria criteria, Pageable pageable) {
    log.debug("Admin searching listings with criteria={}", criteria);
    return listingRepository.findAll(buildSearchSpec(criteria), pageable)
        .map(listingMapper::toSummaryResponse);
  }

  @Override
  @Transactional
  public ListingSummaryResponse updateStatus(Long id, ListingStatus status, String adminId) {
    Listing listing = listingRepository.findById(id)
        .orElseThrow(() -> new ListingNotFoundException(id));
    listing.setStatus(status);
    saveWithConflictCheck(listing, id);
    log.info("Admin action: action=updateListingStatus, listingId={}, status={}, adminId={}",
        id, status, adminId);
    adminAuditLogService.record(
        "updateListingStatus", AdminAuditObjectType.LISTING, String.valueOf(id), Long.parseLong(adminId));
    return listingMapper.toSummaryResponse(listing);
  }

  @Override
  @Transactional
  public void unlinkDuplicateGroup(Long id, String adminId) {
    Listing listing = listingRepository.findById(id)
        .orElseThrow(() -> new ListingNotFoundException(id));
    listing.setDedupHash(null);
    saveWithConflictCheck(listing, id);
    log.info("Admin action: action=unlinkDuplicateGroup, listingId={}, adminId={}", id, adminId);
    adminAuditLogService.record(
        "unlinkDuplicateGroup", AdminAuditObjectType.LISTING, String.valueOf(id), Long.parseLong(adminId));
  }

  /**
   * Flushes the listing update immediately so a version conflict with a concurrent writer (e.g.
   * the ingestion sync job) surfaces here as {@link ListingConcurrentModificationException},
   * instead of only at transaction commit after this method has already returned.
   *
   * @param listing the listing with pending in-memory changes
   * @param id      the listing id, used for the conflict error message
   * @throws ListingConcurrentModificationException if the row's version was changed concurrently
   */
  private void saveWithConflictCheck(Listing listing, Long id) {
    try {
      listingRepository.saveAndFlush(listing);
    } catch (OptimisticLockingFailureException e) {
      log.warn("Concurrent modification conflict on listingId={}", id, e);
      throw new ListingConcurrentModificationException(id);
    }
  }

  private Specification<Listing> buildSearchSpec(AdminListingSearchCriteria criteria) {
    return (root, query, cb) -> {
      applyFetchJoins(root, query);
      List<Predicate> predicates = new ArrayList<>();
      addEqualityPredicates(root, cb, criteria, predicates);
      addRangePredicates(root, cb, criteria, predicates);
      addTextPredicates(root, cb, criteria, predicates);
      addDuplicatePredicate(root, query, cb, criteria, predicates);
      return cb.and(predicates.toArray(new Predicate[0]));
    };
  }

  /**
   * Eagerly fetches {@code source}/{@code currency} for the row-returning query, skipped for the
   * {@code count(id)} query Spring Data issues alongside it — a fetch join there would be wasted
   * and {@code distinct} on a scalar result is meaningless.
   *
   * @param root  the query root
   * @param query the query being built; its result type distinguishes the two cases above
   */
  private void applyFetchJoins(Root<Listing> root, CriteriaQuery<?> query) {
    if (!Long.class.equals(query.getResultType())) {
      root.fetch("source", JoinType.INNER);
      root.fetch("currency", JoinType.INNER);
      query.distinct(true);
    }
  }

  private void addEqualityPredicates(Root<Listing> root, CriteriaBuilder cb,
      AdminListingSearchCriteria criteria, List<Predicate> predicates) {
    if (criteria.status() != null) {
      predicates.add(cb.equal(root.get("status"), criteria.status()));
    }
    if (criteria.dealType() != null) {
      predicates.add(cb.equal(root.get("dealType"), criteria.dealType()));
    }
    if (criteria.propertyType() != null) {
      predicates.add(cb.equal(root.get("propertyType"), criteria.propertyType()));
    }
    if (criteria.rooms() != null) {
      predicates.add(cb.equal(root.get("rooms"), criteria.rooms()));
    }
    if (criteria.sourceId() != null) {
      predicates.add(cb.equal(root.get("source").get("code"), criteria.sourceId()));
    }
  }

  private void addRangePredicates(Root<Listing> root, CriteriaBuilder cb,
      AdminListingSearchCriteria criteria, List<Predicate> predicates) {
    if (criteria.priceMin() != null) {
      predicates.add(cb.greaterThanOrEqualTo(root.get("price"), criteria.priceMin()));
    }
    if (criteria.priceMax() != null) {
      predicates.add(cb.lessThanOrEqualTo(root.get("price"), criteria.priceMax()));
    }
    if (criteria.areaMin() != null) {
      predicates.add(cb.greaterThanOrEqualTo(root.get("areaTotalM2"), criteria.areaMin()));
    }
    if (criteria.areaMax() != null) {
      predicates.add(cb.lessThanOrEqualTo(root.get("areaTotalM2"), criteria.areaMax()));
    }
  }

  private void addTextPredicates(Root<Listing> root, CriteriaBuilder cb,
      AdminListingSearchCriteria criteria, List<Predicate> predicates) {
    if (criteria.query() != null && !criteria.query().isBlank()) {
      predicates.add(buildKeywordPredicate(root, cb, criteria.query()));
    }
    if (criteria.city() != null && !criteria.city().isBlank()) {
      predicates.add(cb.like(cb.lower(root.get("city")),
          LikePatternUtils.containsPattern(criteria.city().toLowerCase()), LikePatternUtils.ESCAPE_CHAR));
    }
  }

  private void addDuplicatePredicate(Root<Listing> root, CommonAbstractCriteria query, CriteriaBuilder cb,
      AdminListingSearchCriteria criteria, List<Predicate> predicates) {
    if (Boolean.TRUE.equals(criteria.duplicatesOnly())) {
      predicates.add(cb.isNotNull(root.get("dedupHash")));
      predicates.add(cb.exists(buildDuplicateExistsSubquery(query, cb, root)));
    }
  }

  /**
   * Builds a case-insensitive substring match against title, description and address.
   *
   * <p>Deliberately a plain {@code LIKE}, not the public search's PostgreSQL full-text
   * {@code websearch_to_tsquery} — that query is a native SQL statement hardcoded to a mandatory,
   * always-ACTIVE status filter (see {@code ListingRepository#fullTextSearch}), which does not fit
   * this endpoint's "any status, keyword optional" search. A plain substring match is also more
   * predictable for moderation use than ranked relevance.
   *
   * @param root  the query root, used to reach the title/description/address columns
   * @param cb    the shared criteria builder
   * @param query the raw keyword string, never null or blank
   * @return a predicate matching any of the three columns
   */
  private Predicate buildKeywordPredicate(Root<Listing> root, CriteriaBuilder cb, String query) {
    String pattern = LikePatternUtils.containsPattern(query.toLowerCase());
    char escape = LikePatternUtils.ESCAPE_CHAR;
    return cb.or(
        cb.like(cb.lower(root.get("title")), pattern, escape),
        cb.like(cb.lower(cb.coalesce(root.get("description"), "")), pattern, escape),
        cb.like(cb.lower(cb.coalesce(root.get("address"), "")), pattern, escape)
    );
  }

  /**
   * Builds a correlated subquery matching another listing with the same non-null dedup hash.
   *
   * <p>Used to filter for listings that are part of a duplicate group — i.e. at least one other
   * listing (any source, any status) shares the same {@code dedupHash}.
   *
   * @param query the outer criteria query, used to derive the subquery
   * @param cb    the shared criteria builder
   * @param root  the outer query's Listing root
   * @return a subquery selecting the id of a matching duplicate, for use with {@code cb.exists}
   */
  private Subquery<Long> buildDuplicateExistsSubquery(
      CommonAbstractCriteria query, CriteriaBuilder cb, Root<Listing> root) {
    Subquery<Long> subquery = query.subquery(Long.class);
    Root<Listing> other = subquery.from(Listing.class);
    subquery.select(other.get("id"));
    subquery.where(
        cb.equal(other.get("dedupHash"), root.get("dedupHash")),
        cb.notEqual(other.get("id"), root.get("id"))
    );
    return subquery;
  }
}
