package com.flatio.service.domain;

import com.flatio.domain.source.SyncRunStatus;
import com.flatio.domain.source.SyncType;
import java.time.Instant;

/**
 * Command object used to record a completed sync run.
 *
 * @param sourceId         connector source identifier
 * @param syncType         FULL or DELTA
 * @param status           outcome of the run
 * @param startedAt        when the run began
 * @param finishedAt       when the run ended
 * @param listingsFetched  total listings received from the source
 * @param listingsAdded    new listings persisted
 * @param listingsUpdated  existing listings refreshed
 * @param listingsErrors   listings that failed to ingest
 */
public record SyncRunRequest(
    String sourceId,
    SyncType syncType,
    SyncRunStatus status,
    Instant startedAt,
    Instant finishedAt,
    int listingsFetched,
    int listingsAdded,
    int listingsUpdated,
    int listingsErrors
) {

  /**
   * Creates a SUCCESS run request from a batch ingest result.
   *
   * @param sourceId   connector source identifier
   * @param syncType   FULL or DELTA
   * @param startedAt  when the run began
   * @param finishedAt when the run ended
   * @param fetched    total listings received from the source
   * @param result     batch ingestion counts
   * @return a SUCCESS-status request
   */
  public static SyncRunRequest success(String sourceId, SyncType syncType,
      Instant startedAt, Instant finishedAt,
      int fetched, BatchIngestResult result) {
    return new SyncRunRequest(sourceId, syncType, SyncRunStatus.SUCCESS,
        startedAt, finishedAt, fetched, result.added(), result.updated(), result.errors());
  }

  /**
   * Creates a FAILURE run request for a sync that did not complete successfully.
   *
   * @param sourceId   connector source identifier
   * @param syncType   FULL or DELTA
   * @param startedAt  when the run began
   * @param finishedAt when the run failed
   * @return a FAILURE-status request with zero listing counts
   */
  public static SyncRunRequest failure(String sourceId, SyncType syncType,
      Instant startedAt, Instant finishedAt) {
    return new SyncRunRequest(sourceId, syncType, SyncRunStatus.FAILURE,
        startedAt, finishedAt, 0, 0, 0, 0);
  }
}
