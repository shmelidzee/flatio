package com.flatio.domain.alert;

/**
 * Kind of source-health problem an alert tracks (issue #419).
 *
 * <p>Each source can have at most one active {@link SourceAlertState} row per type — the two
 * rules are independent, so a source can be alerting on both at once.
 */
public enum AlertType {
  /** No successful sync run recorded within the configured freshness window. */
  NO_SUCCESSFUL_SYNC,
  /** Share of failed runs within the configured sliding window exceeds the configured threshold. */
  HIGH_ERROR_RATE
}
