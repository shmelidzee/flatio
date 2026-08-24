package com.flatio.service.impl;

import com.flatio.integration.core.RawListing;
import com.flatio.domain.currency.Currency;
import com.flatio.domain.listing.DealType;
import com.flatio.domain.listing.Listing;
import com.flatio.domain.listing.ListingStatus;
import com.flatio.domain.listing.PriceUnit;
import com.flatio.domain.listing.PriceHistory;
import com.flatio.domain.source.Source;
import com.flatio.repository.CurrencyRepository;
import com.flatio.repository.ListingRepository;
import com.flatio.repository.PriceHistoryRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.flatio.service.DedupHashService;
import com.flatio.service.ListingIngestionService;
import com.flatio.service.domain.BatchIngestResult;
import com.flatio.service.domain.IngestOutcome;
import com.flatio.integration.core.RawListingMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@Slf4j
@RequiredArgsConstructor
public class ListingIngestionServiceImpl implements ListingIngestionService {

  private static final String LISTING_UNIQUE_CONSTRAINT_NAME = "uq_listing_external_source";

  private final ListingRepository listingRepository;
  private final PriceHistoryRepository priceHistoryRepository;
  private final CurrencyRepository currencyRepository;
  private final RawListingMapper rawListingMapper;
  private final DedupHashService dedupHashService;

  /**
   * Self-reference injected lazily to ensure calls from {@link #ingestOne} go through
   * the Spring AOP proxy, which activates the {@code @Transactional} behaviour on {@link #ingest}.
   */
  @Lazy
  @Autowired
  private ListingIngestionService self;

  @Value("${flatio.sync.inactive-threshold:1}")
  private int inactiveThreshold;

  @Override
  @Transactional
  public IngestOutcome ingest(RawListing raw, Source source) {
    if (!DealType.isKnown(raw.dealType())) {
      log.warn("Unknown deal_type, skipping listing: source={}, id={}, deal_type={}",
          source.getCode(), raw.externalId(), raw.dealType());
      return IngestOutcome.SKIPPED;
    }

    Currency currency = resolveCurrency(raw.currency());
    Optional<Listing> existing = listingRepository.findByExternalIdAndSourceId(
        raw.externalId(), source.getId());

    if (existing.isEmpty()) {
      return createListing(raw, source, currency);
    }
    return updateListing(existing.get(), raw, currency);
  }

  @Override
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public BatchIngestResult ingestBatch(List<RawListing> rawListings, Source source) {
    IngestCounters counters = new IngestCounters();
    for (RawListing raw : rawListings) {
      ingestOne(raw, source, counters);
    }
    log.info("Batch ingestion complete: source={}, added={}, updated={}, errors={}",
        source.getCode(), counters.added, counters.updated, counters.errors);
    return new BatchIngestResult(counters.added, counters.updated, counters.errors);
  }

  private void ingestOne(RawListing raw, Source source, IngestCounters counters) {
    try {
      counters.apply(self.ingest(raw, source));
    } catch (DataIntegrityViolationException e) {
      // Only a real concurrent INSERT race on the dedup unique constraint is retried — other
      // constraint violations (e.g. NOT NULL) would fail identically on retry (issue #379).
      if (isConcurrentInsertConflict(e)) {
        counters.apply(retryAfterConflict(raw, source));
      } else {
        counters.errors++;
        log.error("Non-retryable constraint violation ingesting listing: externalId={}, source={}",
            raw.externalId(), source.getCode(), e);
      }
    } catch (OptimisticLockingFailureException e) {
      // Concurrent UPDATE (e.g. admin moderation) on the same listing — retry against the
      // latest row version in a fresh transaction.
      counters.apply(retryAfterConflict(raw, source));
    } catch (Exception e) {
      counters.errors++;
      log.error("Failed to ingest listing: externalId={}, source={}",
          raw.externalId(), source.getCode(), e);
    }
  }

  /**
   * Distinguishes a genuine concurrent-insert race from any other constraint violation.
   *
   * <p>Walks the full cause chain rather than {@code getMostSpecificCause()}: Hibernate's
   * {@link ConstraintViolationException} is typically the direct cause here, itself wrapping
   * the raw {@link java.sql.SQLException} as its own cause — the most specific (deepest) cause
   * would be that SQLException, not the constraint-bearing exception.
   *
   * @param e the violation caught while ingesting a listing
   * @return true only if the violated constraint is the dedup unique key, safe to retry
   */
  private boolean isConcurrentInsertConflict(DataIntegrityViolationException e) {
    for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
      if (cause instanceof ConstraintViolationException cve) {
        return LISTING_UNIQUE_CONSTRAINT_NAME.equals(cve.getConstraintName());
      }
    }
    return false;
  }

  private IngestOutcome retryAfterConflict(RawListing raw, Source source) {
    log.debug("Concurrent write conflict, retrying ingest: externalId={}, source={}",
        raw.externalId(), source.getCode());
    try {
      return self.ingest(raw, source);
    } catch (Exception e) {
      log.error("Failed to ingest listing after conflict retry: externalId={}, source={}",
          raw.externalId(), source.getCode(), e);
      return null;
    }
  }

  /**
   * Mutable per-batch tally of {@link #ingestBatch} outcomes.
   */
  private static final class IngestCounters {
    private int added;
    private int updated;
    private int errors;

    void apply(IngestOutcome outcome) {
      if (outcome == IngestOutcome.CREATED) {
        added++;
      } else if (outcome == IngestOutcome.UPDATED) {
        updated++;
      } else if (outcome == null) {
        errors++;
      }
    }
  }

  private IngestOutcome createListing(RawListing raw, Source source, Currency currency) {
    Listing listing = rawListingMapper.toEntity(raw);
    listing.setSource(source);
    listing.setCurrency(currency);
    listing.setCountry(source.getCountry());
    listing.setStatus(ListingStatus.ACTIVE);
    listing.setPriceUnit(derivePriceUnit(listing.getDealType()));
    listing.setDedupHash(computeDedupHash(listing));
    detectRepost(listing, source);

    listingRepository.save(listing);
    recordPriceHistory(listing, currency);

    log.debug("Created listing: externalId={}, source={}", listing.getExternalId(), source.getCode());
    return IngestOutcome.CREATED;
  }

  private IngestOutcome updateListing(Listing existing, RawListing raw, Currency currency) {
    boolean priceChanged = isPriceChanged(existing, raw);

    rawListingMapper.updateEntity(raw, existing);
    existing.setCurrency(currency);
    existing.setStatus(ListingStatus.ACTIVE);
    existing.setMissedSyncsCount(0);
    existing.setPriceUnit(derivePriceUnit(existing.getDealType()));
    existing.setDedupHash(computeDedupHash(existing));

    if (priceChanged) {
      recordPriceHistory(existing, currency);
      log.debug("Price changed for listing: externalId={}, source={}",
          existing.getExternalId(), existing.getSource().getCode());
    }

    listingRepository.save(existing);
    return IngestOutcome.UPDATED;
  }

  /**
   * Determines whether the seller's actual price has changed.
   *
   * <p>When a source provides a USD price ({@code priceUsd} is non-null on both sides), the
   * comparison is done in USD — the originating currency. This prevents a false positive when
   * only the BYN-equivalent changed due to exchange-rate fluctuation while the seller's USD
   * price stayed the same.
   *
   * @param existing persisted listing state
   * @param raw      freshly fetched raw listing
   * @return true if the seller changed the price, false if only the exchange rate moved
   */
  private boolean isPriceChanged(Listing existing, RawListing raw) {
    if (raw.priceUsd() != null && existing.getPriceUsd() != null) {
      boolean usdUnchanged = existing.getPriceUsd().compareTo(raw.priceUsd()) == 0;
      if (usdUnchanged) {
        log.debug("Skipping price_history: source price unchanged, exchange rate only: externalId={}",
            existing.getExternalId());
        return false;
      }
      return true;
    }
    if (raw.price() == null) {
      log.warn("Source did not provide a price, cannot compare: externalId={}", existing.getExternalId());
      return false;
    }
    return existing.getPrice().compareTo(raw.price()) != 0;
  }

  private void recordPriceHistory(Listing listing, Currency currency) {
    PriceHistory history = new PriceHistory();
    history.setListing(listing);
    history.setPrice(listing.getPrice());
    history.setCurrency(currency);
    priceHistoryRepository.save(history);
  }

  private String computeDedupHash(Listing listing) {
    return dedupHashService.computeDedupHash(
        listing.getAddress(),
        listing.getRooms(),
        listing.getAreaTotalM2(),
        listing.getDealType()
    );
  }

  @Override
  public long countBySource(Source source) {
    return listingRepository.countBySource(source);
  }

  @Override
  @Transactional
  public int deactivateMissing(Source source, Set<String> activeExternalIds) {
    if (activeExternalIds.isEmpty()) {
      return 0;
    }
    int count = listingRepository.deactivateActiveBySourceExcluding(source, activeExternalIds);
    if (count > 0) {
      log.info("Deactivated missing listings: source={}, count={}", source.getCode(), count);
    }
    return count;
  }

  @Override
  @Transactional
  public int applyMissedSyncPenalty(Source source, Set<String> activeExternalIds) {
    if (activeExternalIds.isEmpty()) {
      return 0;
    }
    int incremented = listingRepository.incrementMissedSyncsForAbsent(source, activeExternalIds);
    int deactivated = listingRepository.deactivateByMissedSyncsThreshold(source, inactiveThreshold);
    log.info("Missed sync penalty applied: source={}, incremented={}, deactivated={}, threshold={}",
        source.getCode(), incremented, deactivated, inactiveThreshold);
    return deactivated;
  }

  private void detectRepost(Listing listing, Source source) {
    if (listing.getDedupHash() == null) {
      return;
    }
    Optional<Listing> original = listingRepository
        .findFirstByDedupHashAndSourceAndExternalIdNotAndStatus(
            listing.getDedupHash(), source, listing.getExternalId(), ListingStatus.ACTIVE);
    if (original.isEmpty()) {
      return;
    }
    Listing originalListing = original.get();
    listing.setStatus(ListingStatus.REPOSTED);
    listing.setRepostedFrom(originalListing.getId());
    originalListing.setLastRepostedAt(Instant.now());
    listingRepository.save(originalListing);
    log.info("Repost detected: original={}, repost={}, source={}",
        originalListing.getId(), listing.getExternalId(), source.getCode());
    // FR-SUB-4: subscription notification stub — to be implemented in M2.3
  }

  private static PriceUnit derivePriceUnit(DealType dealType) {
    if (dealType == null) {
      return null;
    }
    return switch (dealType) {
      case RENT -> PriceUnit.PER_MONTH;
      case RENT_DAILY -> PriceUnit.PER_DAY;
      case SELL -> null;
    };
  }

  private Currency resolveCurrency(String code) {
    return currencyRepository.findByCode(code)
        .orElseThrow(() -> new IllegalArgumentException("Unknown currency code: " + code));
  }
}
