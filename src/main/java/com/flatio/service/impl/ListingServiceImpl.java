package com.flatio.service.impl;

import com.flatio.common.exception.ListingNotFoundException;
import com.flatio.common.util.LikePatternUtils;
import com.flatio.domain.blacklist.BlacklistEntry;
import com.flatio.domain.blacklist.BlacklistEntryType;
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
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
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
  private static final String CURRENCY_USD = "USD";
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
  public ListingResponse findById(Long id, String targetCurrency) {
    Listing listing = listingRepository.findById(id)
        .orElseThrow(() -> new ListingNotFoundException(id));
    List<PriceHistory> history = priceHistoryRepository.findByListingOrderByRecordedAtDesc(listing);
    List<PriceHistoryEntry> historyEntries = history.stream()
        .map(listingMapper::toHistoryEntry)
        .toList();
    boolean hasDuplicates = listing.getDedupHash() != null
        && listingRepository.existsByDedupHashAndIdNot(listing.getDedupHash(), listing.getId());
    ListingResponse response = listingMapper.toResponse(listing, historyEntries, hasDuplicates);
    return applyDisplayCurrency(response, targetCurrency);
  }

  @Override
  public Page<ListingSummaryResponse> search(ListingSearchCriteria criteria, Pageable pageable, Long userId,
      String targetCurrency) {
    log.debug("Searching listings with criteria={}, userId={}", criteria, userId);
    BigDecimal usdToByn = currencyRateService.getUsdToByn().orElse(null);
    if (criteria.query() != null && !criteria.query().isBlank()) {
      return searchWithFts(criteria, pageable, usdToByn, userId, targetCurrency);
    }
    return listingRepository.findAll(buildSearchSpec(criteria, userId, usdToByn), pageable)
        .map(l -> applyDisplayCurrency(enrichPrices(listingMapper.toSummaryResponse(l), usdToByn), targetCurrency));
  }

  private Page<ListingSummaryResponse> searchWithFts(ListingSearchCriteria criteria, Pageable pageable,
      BigDecimal usdToByn, Long userId, String targetCurrency) {
    ListingStatus effectiveStatus = criteria.status() != null ? criteria.status() : ListingStatus.ACTIVE;
    String dealType = criteria.dealType() != null ? criteria.dealType().name() : null;
    String cityPattern = criteria.city() != null && !criteria.city().isBlank()
        ? LikePatternUtils.containsPattern(criteria.city().toLowerCase()) : null;
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
        userId,
        toNativePageable(pageable)
    );
    primeSourceAndCurrencyCache(page.getContent());
    return page.map(l -> applyDisplayCurrency(enrichPrices(listingMapper.toSummaryResponse(l), usdToByn), targetCurrency));
  }

  /**
   * Applies both cross-currency display enrichments to a summary response.
   *
   * @param response the summary response produced by the mapper, never null
   * @param usdToByn current USD→BYN rate from NBRB, or null if the rate fetch failed
   * @return the response with {@code priceUsd}/{@code priceByn} populated where missing, never null
   */
  private ListingSummaryResponse enrichPrices(ListingSummaryResponse response, BigDecimal usdToByn) {
    return enrichWithPriceByn(enrichWithPriceUsd(response, usdToByn), usdToByn);
  }

  /**
   * Converts a summary response's stored price into {@code targetCurrency} (BYN when null),
   * issue #415. Read-time only — never touches the stored price/currency.
   *
   * @param response       the summary response to enrich, never null
   * @param targetCurrency ISO currency code to convert into, or null for BYN
   * @return the response with {@code displayPrice}/{@code displayCurrency} populated, or with
   *     both null when the listing has no price (isNegotiable) or the required rate is unavailable
   */
  private ListingSummaryResponse applyDisplayCurrency(ListingSummaryResponse response, String targetCurrency) {
    String target = targetCurrency != null ? targetCurrency : CurrencyRateService.BYN;
    BigDecimal displayPrice = resolveDisplayPrice(response.price(), response.currency(),
        Boolean.TRUE.equals(response.isNegotiable()), target);
    return new ListingSummaryResponse(
        response.id(), response.title(), response.price(), response.currency(),
        response.priceUsd(), response.priceByn(), response.rooms(), response.propertyType(),
        response.areaTotalM2(), response.city(), response.district(), response.address(),
        response.sourceId(), response.publishedAt(), response.photoUrl(), response.sourceUrl(),
        response.isNegotiable(), displayPrice, target
    );
  }

  /**
   * Converts a full listing response's stored price into {@code targetCurrency} (BYN when null),
   * issue #415. Read-time only — never touches the stored price/currency.
   *
   * @param response       the listing response to enrich, never null
   * @param targetCurrency ISO currency code to convert into, or null for BYN
   * @return the response with {@code displayPrice}/{@code displayCurrency} populated, or with
   *     both null when the listing has no price (isNegotiable) or the required rate is unavailable
   */
  private ListingResponse applyDisplayCurrency(ListingResponse response, String targetCurrency) {
    String target = targetCurrency != null ? targetCurrency : CurrencyRateService.BYN;
    BigDecimal displayPrice = resolveDisplayPrice(response.price(), response.currency(),
        Boolean.TRUE.equals(response.isNegotiable()), target);
    return new ListingResponse(
        response.id(), response.externalId(), response.sourceId(), response.title(), response.description(),
        response.dealType(), response.priceUnit(), response.propertyType(), response.price(), response.priceLabel(),
        response.currency(), response.rooms(), response.floorNumber(), response.floorsTotal(),
        response.areaTotalM2(), response.address(), response.city(), response.district(), response.latitude(),
        response.longitude(), response.isOwner(), response.isNegotiable(), response.status(), response.sourceUrl(),
        response.publishedAt(), response.createdAt(), response.priceHistory(), response.hasDuplicates(),
        displayPrice, target
    );
  }

  @Override
  public Map<Long, String> findDisplayLabelsByIds(Collection<Long> ids) {
    if (ids.isEmpty()) {
      return Map.of();
    }
    return listingRepository.findAllById(ids).stream()
        .map(listing -> Map.entry(listing.getId(), resolveDisplayLabel(listing)))
        .filter(entry -> entry.getValue() != null)
        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
  }

  private String resolveDisplayLabel(Listing listing) {
    if (listing.getTitle() != null && !listing.getTitle().isBlank()) {
      return listing.getTitle();
    }
    if (listing.getAddress() != null && !listing.getAddress().isBlank()) {
      return listing.getAddress();
    }
    return null;
  }

  private BigDecimal resolveDisplayPrice(BigDecimal price, String currency, boolean isNegotiable, String targetCurrency) {
    if (isNegotiable || price == null) {
      return null;
    }
    return currencyRateService.convert(price, currency, targetCurrency).orElse(null);
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
   * Computes a BYN display price for USD-priced listings whose stored {@code priceByn} is null
   * (issue #517) — e.g. a Realt.by listing ingested while the NBRB rate was temporarily
   * unavailable (see {@code RealtHtmlParser#computePriceByn}), which otherwise permanently shows
   * as USD-only even after the rate recovers. Mirrors {@link #enrichWithPriceUsd} for the
   * opposite direction so every card can show a consistent {@code $X (Y BYN)} price regardless of
   * when the listing was ingested. Read-time only — never touches the stored price/currency.
   *
   * @param response the summary response produced by the mapper, never null
   * @param usdToByn current USD→BYN rate from NBRB, or null if the rate fetch failed
   * @return the response with {@code priceByn} populated, or the original if enrichment is skipped
   */
  private ListingSummaryResponse enrichWithPriceByn(ListingSummaryResponse response, BigDecimal usdToByn) {
    if (response.priceByn() != null || usdToByn == null || Boolean.TRUE.equals(response.isNegotiable())
        || response.price() == null || !CURRENCY_USD.equals(response.currency())) {
      return response;
    }
    BigDecimal priceByn = response.price().multiply(usdToByn).setScale(USD_DISPLAY_SCALE, RoundingMode.HALF_UP);
    return new ListingSummaryResponse(
        response.id(), response.title(), response.price(), response.currency(),
        response.priceUsd(), priceByn, response.rooms(), response.propertyType(),
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
   * <p>Effective price is {@code COALESCE(priceByn, <derived>)}. For a USD-priced listing whose
   * stored {@code priceByn} is null (issue #528 — e.g. a Realt listing ingested while the NBRB
   * rate was unavailable) and a live rate is available now ({@code usdToByn != null}),
   * {@code <derived>} is {@code price * usdToByn} — computed on the fly, exactly like
   * {@code ListingServiceImpl#enrichWithPriceByn} does for display, so the listing is compared
   * against its real converted price instead of silently passing the filter.
   *
   * <p>Only when {@code usdToByn} is itself null — no NBRB rate has ever been recorded, a rare
   * fresh-install case, since {@link CurrencyRateService#getRate} otherwise falls back to the
   * last known rate — does a listing with no stored {@code priceByn} and a non-BYN currency fall
   * back to the old behaviour: pass the filter unconditionally rather than being wrongly excluded
   * or compared on the raw non-BYN amount.
   *
   * @param cb       JPA criteria builder
   * @param root     root of the query over {@link Listing}
   * @param criteria search criteria with optional price bounds
   * @param usdToByn current USD→BYN rate from NBRB, or null if no rate has ever been recorded
   * @return list of price predicates, empty when both bounds are null
   */
  private List<Predicate> buildPricePredicates(CriteriaBuilder cb, Root<Listing> root,
      ListingSearchCriteria criteria, BigDecimal usdToByn) {
    if (criteria.priceMin() == null && criteria.priceMax() == null) {
      return List.of();
    }
    Expression<BigDecimal> effectivePrice;
    Predicate noRateAvailable;
    if (usdToByn != null) {
      Expression<BigDecimal> derivedFromUsdRate = cb.prod(root.get("price"), usdToByn);
      Expression<BigDecimal> nonBynFallback = cb.<BigDecimal>selectCase()
          .when(cb.equal(root.get("currency").<String>get("code"), CURRENCY_USD), derivedFromUsdRate)
          .otherwise(root.get("price"));
      effectivePrice = cb.coalesce(root.get("priceByn"), nonBynFallback);
      noRateAvailable = cb.disjunction();
    } else {
      effectivePrice = cb.coalesce(root.get("priceByn"), root.get("price"));
      noRateAvailable = cb.and(
          cb.isNull(root.get("priceByn")),
          cb.notEqual(root.get("currency").<String>get("code"), CURRENCY_BYN)
      );
    }
    List<Predicate> result = new ArrayList<>();
    // Negotiable listings have no meaningful price — exclude them when any price filter is active.
    result.add(cb.notEqual(root.get("isNegotiable"), Boolean.TRUE));
    if (criteria.priceMin() != null) {
      result.add(cb.or(noRateAvailable, cb.greaterThanOrEqualTo(effectivePrice, criteria.priceMin())));
    }
    if (criteria.priceMax() != null) {
      result.add(cb.or(noRateAvailable, cb.lessThanOrEqualTo(effectivePrice, criteria.priceMax())));
    }
    return result;
  }

  private Specification<Listing> buildSearchSpec(ListingSearchCriteria criteria, Long userId, BigDecimal usdToByn) {
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
      predicates.addAll(buildPricePredicates(cb, root, criteria, usdToByn));
      if (criteria.city() != null && !criteria.city().isBlank()) {
        predicates.add(cb.like(cb.lower(root.get("city")),
            LikePatternUtils.containsPattern(criteria.city().toLowerCase()), LikePatternUtils.ESCAPE_CHAR));
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
      if (userId != null) {
        predicates.add(cb.not(cb.exists(buildBlacklistSubquery(cb, query, root, userId))));
      }

      return cb.and(predicates.toArray(new Predicate[0]));
    };
  }

  /**
   * Builds a correlated subquery over {@link BlacklistEntry} matching the given user's excluded
   * listings (by ID), sources (by code), or stop-words (substring match against title,
   * description, or address) — negated by the caller into a {@code NOT EXISTS} predicate so the
   * exclusion is enforced by the database query itself (issue #414), not by filtering the result
   * page in memory.
   *
   * @param cb        JPA criteria builder
   * @param query     the outer query (data or count) the subquery is correlated against
   * @param listing   root of the outer query over {@link Listing}
   * @param userId    the caller whose blacklist to match against
   * @return subquery selecting the ID of any matching blacklist entry
   */
  private Subquery<Long> buildBlacklistSubquery(CriteriaBuilder cb, CriteriaQuery<?> query, Root<Listing> listing,
      Long userId) {
    Subquery<Long> subquery = query.subquery(Long.class);
    Root<BlacklistEntry> entry = subquery.from(BlacklistEntry.class);
    subquery.select(entry.get("id"));

    Predicate listingMatch = cb.and(
        cb.equal(entry.get("type"), BlacklistEntryType.LISTING),
        cb.equal(entry.get("value"), listing.get("id").as(String.class)));
    Predicate sourceMatch = cb.and(
        cb.equal(entry.get("type"), BlacklistEntryType.SOURCE),
        cb.equal(entry.get("value"), listing.get("source").get("code")));
    Predicate keywordMatch = cb.and(
        cb.equal(entry.get("type"), BlacklistEntryType.KEYWORD),
        cb.or(
            matchesKeywordValue(cb, listing.get("title"), entry.get("value")),
            matchesKeywordValue(cb, listing.get("description"), entry.get("value")),
            matchesKeywordValue(cb, listing.get("address"), entry.get("value"))
        ));

    subquery.where(cb.and(
        cb.equal(entry.get("user").get("id"), userId),
        cb.or(listingMatch, sourceMatch, keywordMatch)
    ));
    return subquery;
  }

  /**
   * Builds a case-insensitive "contains" predicate matching {@code column} against a stop-word
   * value read from a database column rather than a fixed Java string — {@code %}/{@code _} in
   * the stored value are escaped in SQL via {@code replace()} calls mirroring
   * {@link LikePatternUtils#escape(String)}, since that helper only escapes a literal Java string
   * known at query-build time.
   *
   * @param cb     JPA criteria builder
   * @param column the listing column to match against (title, description, or address)
   * @param value  the stop-word column ({@code BlacklistEntry.value}) to match with
   * @return a {@code LIKE ... ESCAPE '\'} predicate, case-insensitive
   */
  private Predicate matchesKeywordValue(CriteriaBuilder cb, Expression<String> column, Expression<String> value) {
    Expression<String> escaped = cb.function("replace", String.class,
        cb.function("replace", String.class,
            cb.function("replace", String.class, value, cb.literal("\\"), cb.literal("\\\\")),
            cb.literal("%"), cb.literal("\\%")),
        cb.literal("_"), cb.literal("\\_"));
    Expression<String> pattern = cb.concat(cb.concat(cb.literal("%"), escaped), "%");
    return cb.like(cb.lower(column), cb.lower(pattern), LikePatternUtils.ESCAPE_CHAR);
  }
}
