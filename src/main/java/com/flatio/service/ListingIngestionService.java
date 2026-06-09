package com.flatio.service;

import com.flatio.integration.core.RawListing;
import com.flatio.domain.source.Source;
import com.flatio.service.domain.BatchIngestResult;
import com.flatio.service.domain.IngestOutcome;

import java.util.List;

/**
 * Handles ingestion of raw connector data into the listing store.
 *
 * <p>Responsible for deduplication, field mapping, upsert logic,
 * and price-history tracking.
 */
public interface ListingIngestionService {

  /**
   * Ingests a single raw listing: maps, deduplicates, and persists.
   *
   * <p>Creates a new listing if none exists for the given
   * {@code (externalId, sourceId)} pair; otherwise updates the existing record.
   * A {@link PriceHistory} entry is written whenever the price changes.
   *
   * @param raw    raw listing data from a connector, must not be null
   * @param source the data source this listing originated from, must not be null
   * @return {@link IngestOutcome#CREATED} or {@link IngestOutcome#UPDATED}
   * @throws IllegalArgumentException if the currency code in {@code raw} is unknown
   */
  IngestOutcome ingest(RawListing raw, Source source);

  /**
   * Ingests a batch of raw listings, isolating errors per item.
   *
   * <p>An error on a single listing is logged and does not abort the batch.
   * Summary statistics are logged at INFO level upon completion.
   *
   * @param rawListings list of raw listings to ingest, must not be null
   * @param source      the data source these listings originated from, must not be null
   * @return summary containing counts of created, updated, and failed items, never null
   */
  BatchIngestResult ingestBatch(List<RawListing> rawListings, Source source);
}
