-- City filter step was removed from the Telegram wizard (cityRef relation never existed on
-- listings, causing search to crash). City is now matched via the free-text keyword search,
-- so it must be part of the indexed search_vector alongside title, description and address.
CREATE OR REPLACE FUNCTION listings_update_search_vector() RETURNS trigger AS $$
BEGIN
  NEW.search_vector := to_tsvector('russian',
    COALESCE(NEW.title, '') || ' ' ||
    COALESCE(NEW.description, '') || ' ' ||
    COALESCE(NEW.address, '') || ' ' ||
    COALESCE(NEW.city, '')
  );
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Trigger must also fire when city changes, not just title/description/address
DROP TRIGGER IF EXISTS listings_search_vector_update ON listings;
CREATE TRIGGER listings_search_vector_update
  BEFORE INSERT OR UPDATE OF title, description, address, city
  ON listings
  FOR EACH ROW EXECUTE FUNCTION listings_update_search_vector();

-- Backfill existing rows
UPDATE listings
SET search_vector = to_tsvector('russian',
  COALESCE(title, '') || ' ' ||
  COALESCE(description, '') || ' ' ||
  COALESCE(address, '') || ' ' ||
  COALESCE(city, '')
);
