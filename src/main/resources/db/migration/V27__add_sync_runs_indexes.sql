CREATE INDEX idx_sync_runs_status_finished_at ON sync_runs (status, finished_at DESC);
CREATE INDEX idx_sync_runs_source_id ON sync_runs (source_id);
