ALTER TABLE listings ADD COLUMN search_vector tsvector;

CREATE INDEX idx_listings_search_vector ON listings USING gin(search_vector);

-- TODO: language 'russian' is hardcoded here because PostgreSQL triggers cannot accept runtime
-- parameters. Multi-language tsvector strategy must be decided before expanding to non-Russian markets.
-- Track as open question for PO before adding a second market.
CREATE OR REPLACE FUNCTION listings_update_search_vector() RETURNS trigger AS $$
BEGIN
  NEW.search_vector := to_tsvector('russian',
    COALESCE(NEW.title, '') || ' ' ||
    COALESCE(NEW.description, '') || ' ' ||
    COALESCE(NEW.address, '')
  );
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER listings_search_vector_update
  BEFORE INSERT OR UPDATE OF title, description, address
  ON listings
  FOR EACH ROW EXECUTE FUNCTION listings_update_search_vector();

-- Backfill existing rows
UPDATE listings
SET search_vector = to_tsvector('russian',
  COALESCE(title, '') || ' ' ||
  COALESCE(description, '') || ' ' ||
  COALESCE(address, '')
);
