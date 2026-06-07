package com.flatio.connector.core;

import java.util.List;

/**
 * Contract for all listing source connectors.
 *
 * <p>Each connector is responsible for fetching raw listing data from one external source.
 * Implementations must apply rate limiting, retry, and per-listing error isolation.
 *
 * <p>The source identifier and region code are configuration-driven — never hard-coded.
 *
 * @see RawListing
 */
public interface ListingConnector {

  /**
   * Returns the unique identifier of the data source this connector handles.
   *
   * <p>Must match the {@code code} field of the corresponding {@code Source} entity
   * (e.g. {@code "ONLINER"}, {@code "REALT"}).
   *
   * @return source code, never null
   */
  String getSourceId();

  /**
   * Returns the ISO region code this connector covers (e.g. {@code "BY"} for Belarus).
   *
   * <p>Region is always injected from configuration — never hard-coded in the implementation.
   *
   * @return region code, never null
   */
  String getSupportedRegionCode();

  /**
   * Fetches current listings from the source.
   *
   * <p>Implementations must:
   * <ul>
   *   <li>Apply rate limiting to avoid overloading the source.</li>
   *   <li>Retry on transient failures with exponential backoff.</li>
   *   <li>Skip individual broken listings without aborting the entire fetch.</li>
   *   <li>Never store raw HTML — return only structured data.</li>
   * </ul>
   *
   * @return list of raw listings, never null, may be empty on source error
   */
  List<RawListing> fetch();
}
