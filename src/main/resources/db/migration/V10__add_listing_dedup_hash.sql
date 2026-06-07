ALTER TABLE listings ADD COLUMN dedup_hash VARCHAR(64);

CREATE INDEX idx_listings_dedup_hash ON listings (dedup_hash);
