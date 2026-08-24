-- sync_interval_minutes (added in V48) was never read by any scheduler — every connector's sync
-- cadence is driven by static flatio.sync.<source>.{delta,full}.cron properties, not this
-- per-source DB column. Dropped as dead, misleading data (issue #387); the corresponding UI
-- health-check dependency on it is fixed separately (issue #390).
ALTER TABLE source
  DROP COLUMN sync_interval_minutes;
