package com.flatio.service.domain;

/** Outcome of a single listing ingestion attempt. */
public enum IngestOutcome {
  CREATED,
  UPDATED,
  /** Listing was intentionally skipped (e.g. unknown deal_type). */
  SKIPPED
}
