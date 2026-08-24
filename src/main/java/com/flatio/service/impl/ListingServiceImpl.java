package com.flatio.service.impl;

import com.flatio.common.exception.ListingNotFoundException;
import com.flatio.domain.currency.Currency;
import com.flatio.domain.listing.Listing;
import com.flatio.domain.listing.ListingStatus;
import com.flatio.domain.listing.PriceHistory;
import com.flatio.domain.source.Source;
import com.flatio.repository.CurrencyRepository;
import com.flatio.repository.ListingRepository;
import com.flatio.repository.PriceHistoryRepository;
import com.flatio.repository.SourceRepository;
import com.flatio.service.CurrencyRateService;
import com.flatio.service.ListingService;
import com.flatio.web.dto.ListingResponse;
import com.flatio.web.dto.ListingSearchCriteria;
import com.flatio.web.dto.ListingSummaryResponse;
import com.flatio.web.dto.PriceHistoryEntry;
import com.flatio.web.mapper.ListingMapper;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@Slf4j
@RequiredArgsConstructor
public class ListingServiceImpl implements ListingService {

  private static final Set<String> ALLOWED_NATIVE_SORT_COLUMNS = Set.of(
      "id", "price", "price_usd", "rooms", "area_total_m2",
      "created_at", "updated_at", "published_at"
  );

  private static final String CURRENCY_BYN = "BYN";
  private static final int USD_DISPLAY_SCALE = 2;

  @Value("${flatio.search.fts-language:russian}")
  private String ftsLanguage;

  private final ListingRepository listingRepository;
  private final PriceHistoryRepository priceHistoryRepository;
  private final SourceRepository sourceRepository;
  private final CurrencyRepository currencyRepository;
  private final ListingMapper listingMapper;
  private final CurrencyRateService currencyRateService;

  @Override
  public ListingResponse findById(Long id) {
    Listing listing = listingRepository.findById(id)
        .orElseThrow(() -> new ListingNotFoundException(id));
    List<PriceHistory> history = priceHistoryRepository.findByListingOrderByRecordedAtDesc(listing);
    List<PriceHistoryEntry> historyEntries = history.stream()
        .map(listingMapper::toHistoryEntry)
        .toList();
    boolean hasDuplicates = listing.getDedupHash() != null
        && listingRepository.existsByDedupHashAndIdNot(listing.getDedupHash(), listing.getId());
    return listingMapper.toResponse(listing, historyEntries, hasDuplicates);
  }

  @Override
  public Page<ListingSummaryResponse> search(ListingSearchCriteria criteria, Pageable pageable) {
    log.debug("Searching listings with criteria={}", criteria);
    BigDecimal usdToByn = currencyRateService.getUsdToByn().orElse(null);
    if (criteria.query() != null && !criteria.query().isBlank()) {
      return searchWithFts(criteria, pageable, usdToByn);
    }
    return listingRepository.findAll(buildSearchSpec(criteria), pageable)
        .map(l -> enrichWithPriceUsd(listingMapper.toSummaryResponse(l), usdToByn));
  }

  private Page<ListingSummaryResponse> searchWithFts(ListingSearchCriteria criteria, Pageable pageable,
      BigDecimal usdToByn) {
    ListingStatus effectiveStatus = criteria.status() != null ? criteria.status() : ListingStatus.ACTIVE;
    String dealType = criteria.dealType() != null ? criteria.dealType().name() : null;
    String cityPattern = criteria.city() != null && !criteria.city().isBlank()
        ? "%" + criteria.city().toLowerCase() + "%" : null;
    Page<Listing> page = listingRepository.fullTextSearch(
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
        Boolean.TRUE.equals(criteria.ownerOnly()) ? Boolean.TRUE : null,
        toNativePageable(pageable)
    );
    primeSourceAndCurrencyCache(page.getContent());
    return page.map(l -> enrichWithPriceUsd(listingMapper.toSummaryResponse(l), usdToByn));
  }

  /**
   * Batch-loads the distinct {@code source} and {@code currency} rows referenced by a page of
   * listings into the current persistence context, so the mapper's per-row
   * {@code getSource().getCode()} / {@code getCurrency().getCode()} lazy-proxy access resolves
   * from the already-loaded entities instead of issuing one extra query per row.
   *
   * <p>{@link ListingRepository#fullTextSearch} is a native query, which cannot use
   * {@code JOIN FETCH} the way the {@code Specification}-based {@link #search} does — this is
   * the batch-fetch alternative called out in issue #377.
   *
   * @param listings the page content just returned by {@code fullTextSearch}, never null
   */
  private void primeSourceAndCurrencyCache(List<Listing> listings) {
    Set<Long> sourceIds = listings.stream()
        .map(Listing::getSource).filter(Objects::nonNull)
        .map(Source::getId).collect(Collectors.toSet());
    Set<Long> currencyIds = listings.stream()
        .map(Listing::getCurrency).filter(Objects::nonNull)
        .map(Currency::getId).collect(Collectors.toSet());
    if (!sourceIds.isEmpty()) {
      sourceRepository.findAllById(sourceIds);
    }
    if (!currencyIds.isEmpty()) {
      currencyRepository.findAllById(currencyIds);
    }
  }

  /**
   * Computes a USD display price for BYN-priced listings using the current NBRB exchange rate.
   *
   * <p>Onliner and similar sources publish prices in BYN. To show a consistent
   * {@code $X (Y BYN)} format across all sources, we derive the USD equivalent at display time
   * when it is not already stored on the listing. Returns the response unchanged when the rate
   * is unavailable or {@code priceUsd} is already set.
   *
   * @param response  the summary response produced by the mapper, never null
   * @param usdToByn  current USD→BYN rate from NBRB, or null if the rate fetch failed
   * @return the response with {@code priceUsd} populated, or the original if enrichment is skipped
   */
  private ListingSummaryResponse enrichWithPriceUsd(ListingSummaryResponse response, BigDecimal usdToByn) {
    if (response.priceUsd() != null || usdToByn == null || Boolean.TRUE.equals(response.isNegotiable())
        || response.price() == null || !CURRENCY_BYN.equals(response.currency())) {
      return response;
    }
    BigDecimal priceUsd = response.price().divide(usdToByn, USD_DISPLAY_SCALE, RoundingMode.HALF_UP);
    return new ListingSummaryResponse(
        response.id(), response.title(), response.price(), response.currency(),
        priceUsd, response.priceByn(), response.rooms(), response.propertyType(),
        response.areaTotalM2(), response.city(), response.district(), response.address(),
        response.sourceId(), response.publishedAt(), response.photoUrl(), response.sourceUrl(),
        response.isNegotiable()
    );
  }

  /**
   * Converts a Pageable's sort from Java camelCase field names to SQL snake_case column names,
   * validating each column against an explicit allowlist to prevent ORDER BY injection.
   *
   * <p>Spring Data JPA does not apply the physical naming strategy to ORDER BY clauses in native
   * queries — sort properties are appended literally, so {@code createdAt} becomes
   * {@code createdat} in PostgreSQL (case-insensitive) which does not match {@code created_at}.
   * This method must be used whenever a Pageable is passed to a {@code nativeQuery = true} method.
   *
   * @param pageable the original pageable, never null
   * @return pageable with sort properties converted to snake_case
   * @throws IllegalArgumentException if a sort property is not in {@link #ALLOWED_NATIVE_SORT_COLUMNS}
   */
  private static Pageable toNativePageable(Pageable pageable) {
    if (!pageable.getSort().isSorted()) {
      return pageable;
    }
    Sort nativeSort = Sort.by(
        pageable.getSort().stream()
            .map(order -> {
              String column = camelToSnake(order.getProperty());
              if (!ALLOWED_NATIVE_SORT_COLUMNS.contains(column)) {
                throw new IllegalArgumentException(
                    "Sort by '" + order.getProperty() + "' is not allowed in native queries");
              }
              return order.withProperty(column);
            })
            .toList()
    );
    return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), nativeSort);
  }

  private static String camelToSnake(String camelCase) {
    return camelCase.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
  }

  /**
   * Builds price range predicates for the Specification-based search.
   *
   * <p>Uses {@code COALESCE(priceByn, price)} as the effective price when the stored BYN
   * conversion is available. When a listing has no BYN price and its currency is not BYN
   * (e.g., a USD-priced Realt listing where the NBRB rate was unavailable at ingestion time),
   * comparing the raw USD value against a BYN filter would produce wrong results, so such
   * listings are excluded from the price filter altogether.
   *
   * @param cb       JPA criteria builder
   * @param root     root of the query over {@link Listing}
   * @param criteria search criteria with optional price bounds
   * @return list of price predicates, empty when both bounds are null
   */
  private List<Predicate> buildPricePredicates(CriteriaBuilder cb, Root<Listing> root,
      ListingSearchCriteria criteria) {
    if (criteria.priceMin() == null && criteria.priceMax() == null) {
      return List.of();
    }
    Expression<BigDecimal> effectivePrice = cb.coalesce(root.get("priceByn"), root.get("price"));
    Predicate noBynConversion = cb.and(
        cb.isNull(root.get("priceByn")),
        cb.notEqual(root.get("currency").<String>get("code"), CURRENCY_BYN)
    );
    List<Predicate> result = new ArrayList<>();
    // Negotiable listings have no meaningful price — exclude them when any price filter is active.
    result.add(cb.notEqual(root.get("isNegotiable"), Boolean.TRUE));
    if (criteria.priceMin() != null) {
      result.add(cb.or(noBynConversion, cb.greaterThanOrEqualTo(effectivePrice, criteria.priceMin())));
    }
    if (criteria.priceMax() != null) {
      result.add(cb.or(noBynConversion, cb.lessThanOrEqualTo(effectivePrice, criteria.priceMax())));
    }
    return result;
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
      predicates.addAll(buildPricePredicates(cb, root, criteria));
      if (criteria.city() != null && !criteria.city().isBlank()) {
        predicates.add(cb.like(cb.lower(root.get("city")), "%" + criteria.city().toLowerCase() + "%"));
      }
      if (criteria.sourceId() != null) {
        predicates.add(cb.equal(root.get("source").get("code"), criteria.sourceId()));
      }
      if (Boolean.TRUE.equals(criteria.ownerOnly())) {
        // Sources without owner info (isOwner IS NULL) are always included per FR
        predicates.add(cb.or(
            cb.isTrue(root.get("isOwner")),
            cb.isNull(root.get("isOwner"))
        ));
      }

      return cb.and(predicates.toArray(new Predicate[0]));
    };
  }
}
