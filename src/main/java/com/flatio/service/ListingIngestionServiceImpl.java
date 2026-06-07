package com.flatio.service;

import com.flatio.connector.core.RawListing;
import com.flatio.domain.currency.Currency;
import com.flatio.domain.listing.Listing;
import com.flatio.domain.listing.ListingStatus;
import com.flatio.domain.listing.PriceHistory;
import com.flatio.domain.source.Source;
import com.flatio.repository.CurrencyRepository;
import com.flatio.repository.ListingRepository;
import com.flatio.repository.PriceHistoryRepository;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@Slf4j
@RequiredArgsConstructor
public class ListingIngestionServiceImpl implements ListingIngestionService {

  private final ListingRepository listingRepository;
  private final PriceHistoryRepository priceHistoryRepository;
  private final CurrencyRepository currencyRepository;
  private final RawListingMapper rawListingMapper;
  private final DedupHashService dedupHashService;

  /**
   * Self-reference injected lazily to ensure calls from {@link #ingestBatch} go through
   * the Spring AOP proxy, which activates the {@code @Transactional} behaviour on {@link #ingest}.
   */
  @Lazy
  @Autowired
  private ListingIngestionService self;

  @Override
  @Transactional
  public IngestOutcome ingest(RawListing raw, Source source) {
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
    int added = 0, updated = 0, errors = 0;

    for (RawListing raw : rawListings) {
      try {
        IngestOutcome outcome = self.ingest(raw, source);
        if (outcome == IngestOutcome.CREATED) {
          added++;
        } else if (outcome == IngestOutcome.UPDATED) {
          updated++;
        }
      } catch (Exception e) {
        errors++;
        log.error("Failed to ingest listing: externalId={}, source={}",
            raw.externalId(), source.getCode(), e);
      }
    }

    log.info("Batch ingestion complete: source={}, added={}, updated={}, errors={}",
        source.getCode(), added, updated, errors);
    return new BatchIngestResult(added, updated, errors);
  }

  private IngestOutcome createListing(RawListing raw, Source source, Currency currency) {
    Listing listing = rawListingMapper.toEntity(raw);
    listing.setSource(source);
    listing.setCurrency(currency);
    listing.setCountry(source.getCountry());
    listing.setStatus(ListingStatus.ACTIVE);
    listing.setDedupHash(computeDedupHash(listing));

    recordPriceHistory(listing, currency);
    listingRepository.save(listing);

    log.debug("Created listing: externalId={}, source={}", listing.getExternalId(), source.getCode());
    return IngestOutcome.CREATED;
  }

  private IngestOutcome updateListing(Listing existing, RawListing raw, Currency currency) {
    boolean priceChanged = existing.getPrice().compareTo(raw.price()) != 0;

    rawListingMapper.updateEntity(raw, existing);
    existing.setCurrency(currency);
    existing.setStatus(ListingStatus.ACTIVE);
    existing.setDedupHash(computeDedupHash(existing));

    if (priceChanged) {
      recordPriceHistory(existing, currency);
      log.debug("Price changed for listing: externalId={}, source={}",
          existing.getExternalId(), existing.getSource().getCode());
    }

    listingRepository.save(existing);
    return IngestOutcome.UPDATED;
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

  private Currency resolveCurrency(String code) {
    return currencyRepository.findByCode(code)
        .orElseThrow(() -> new IllegalArgumentException("Unknown currency code: " + code));
  }
}
