CREATE INDEX IF NOT EXISTS idx_listings_dedup_hash_source
  ON listings (dedup_hash, source_id)
  WHERE dedup_hash IS NOT NULL;
