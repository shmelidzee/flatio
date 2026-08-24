ALTER TABLE listings
  ADD COLUMN geocoding_failed_attempts INTEGER NOT NULL DEFAULT 0;
