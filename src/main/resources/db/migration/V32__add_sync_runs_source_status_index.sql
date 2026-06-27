CREATE INDEX idx_sync_runs_source_status_finished ON sync_runs (source_id, status, finished_at DESC);
