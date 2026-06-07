package com.flatio.service;

/** Outcome of a single listing ingestion attempt. */
public enum IngestOutcome {
  CREATED,
  UPDATED,
  SKIPPED
}
