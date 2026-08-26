package com.flatio.service.domain;

import com.flatio.domain.listing.Listing;
import com.flatio.domain.listing.ListingStatus;
import java.math.BigDecimal;

/**
 * A single listing change observed during a sync, passed to
 * {@code com.flatio.service.NotificationTriggerService#evaluate(java.util.List)} to decide which
 * subscription notifications to raise.
 *
 * <p>{@code previousStatus} is the listing's status immediately before this change was applied.
 * It is required to detect a REACTIVATED transition (INACTIVE to ACTIVE) and is always null for a
 * {@link ListingChangeType#NEW} change, since a newly created listing has no prior status. This
 * value is not persisted on {@link Listing} (which only stores the current status), so the caller
 * that performed the update — the ingestion service — must supply it.
 *
 * @param listing        the listing after the change was applied, never null
 * @param changeType     whether the listing is newly created or was updated, never null
 * @param previousStatus the listing's status before this change, or null for a NEW change
 * @param oldPrice       the price before the change, or null if price did not change or is unknown
 * @param newPrice       the price after the change, or null if price did not change or is unknown
 */
public record ListingChange(
    Listing listing,
    ListingChangeType changeType,
    ListingStatus previousStatus,
    BigDecimal oldPrice,
    BigDecimal newPrice
) {}
