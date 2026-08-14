CREATE INDEX idx_sync_runs_source_started_at ON sync_runs (source_id, started_at DESC);
