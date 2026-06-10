ALTER TABLE listings
  ADD COLUMN IF NOT EXISTS reposted_from BIGINT,
  ADD COLUMN IF NOT EXISTS last_reposted_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE listings
  ADD CONSTRAINT fk_listing_reposted_from
  FOREIGN KEY (reposted_from) REFERENCES listings(id)
  ON DELETE SET NULL;
