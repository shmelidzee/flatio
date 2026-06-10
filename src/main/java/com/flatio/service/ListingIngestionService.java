package com.flatio.service;

import com.flatio.integration.core.RawListing;
import com.flatio.domain.source.Source;
import com.flatio.service.domain.BatchIngestResult;
import com.flatio.service.domain.IngestOutcome;

import java.util.List;
import java.util.Set;

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

  /**
   * Counts total listings for the given source.
   *
   * @param source the data source, must not be null
   * @return total listing count, never negative
   */
  long countBySource(Source source);

  /**
   * Marks all ACTIVE listings for the given source as INACTIVE if their external ID
   * is absent from {@code activeExternalIds}.
   *
   * <p>Intended for use after a full sync to retire listings that no longer appear
   * in the source's API response. Returns 0 without touching the database when
   * {@code activeExternalIds} is empty (guards against accidental mass-deactivation
   * on a failed fetch).
   *
   * @param source            the data source, must not be null
   * @param activeExternalIds set of external IDs currently present in the source, must not be null
   * @return number of listings deactivated
   */
  int deactivateMissing(Source source, Set<String> activeExternalIds);

  /**
   * Applies the missed-sync penalty after a full sync:
   * increments {@code missedSyncsCount} for absent ACTIVE listings and marks as INACTIVE
   * those that have exceeded the configured threshold.
   *
   * <p>Returns immediately without touching the database when {@code activeExternalIds} is empty
   * — this prevents accidental mass-deactivation when a fetch returns no results.
   *
   * @param source            the data source, must not be null
   * @param activeExternalIds set of external IDs present in the current sync batch, must not be null
   * @return number of listings newly deactivated in this call
   */
  int applyMissedSyncPenalty(Source source, Set<String> activeExternalIds);
}
