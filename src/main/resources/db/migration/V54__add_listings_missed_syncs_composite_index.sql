-- Composite index for ListingRepository.deactivateByMissedSyncsThreshold, which filters on
-- source_id AND status = 'ACTIVE' AND missed_syncs_count >= :threshold. The existing single-column
-- indexes on source_id and status individually do not cover this combined predicate.
CREATE INDEX idx_listing_source_status_missed_syncs ON listings (source_id, status, missed_syncs_count);
