package com.flatio.domain.source;

/** Distinguishes between a full crawl and an incremental delta sync. */
public enum SyncType {
  FULL,
  DELTA
}
