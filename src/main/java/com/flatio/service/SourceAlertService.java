package com.flatio.service;

import com.flatio.domain.alert.AlertType;
import java.time.Duration;

/**
 * Tracks source-health alert state (issue #419) so the checker job can decide whether a rule
 * match should actually produce a new Telegram notification, or is just a continuation of an
 * already-notified problem.
 */
public interface SourceAlertService {

  /**
   * Records that the given rule currently matches for a source, and decides whether this should
   * produce a new notification.
   *
   * <p>Returns true the first time a rule starts matching, and again after {@code cooldown} has
   * elapsed since the last notification while it keeps matching — never on every check, which
   * would otherwise re-notify on every run of the checker job for the whole outage.
   *
   * @param sourceId  connector source identifier
   * @param alertType which rule matched
   * @param cooldown  minimum time between repeat notifications for the same, still-active alert
   * @return true if the caller should send a failure notification now
   */
  boolean registerFailure(String sourceId, AlertType alertType, Duration cooldown);

  /**
   * Records that the given rule no longer matches for a source.
   *
   * @param sourceId  connector source identifier
   * @param alertType which rule stopped matching
   * @return true if an active alert was cleared (the caller should send a recovery notification);
   *     false if the rule was not active (nothing to resolve, no notification needed)
   */
  boolean registerRecovery(String sourceId, AlertType alertType);
}
