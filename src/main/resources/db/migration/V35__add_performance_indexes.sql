-- Composite indexes for the most common filter combinations used in listing search.
-- Without these, each filter clause triggers a separate sequential scan; with them
-- PostgreSQL can satisfy multi-column predicates from a single index scan.
CREATE INDEX idx_listings_status_deal_type ON listings (status, deal_type);
CREATE INDEX idx_listings_status_country ON listings (status, country_id);
CREATE INDEX idx_listings_status_rooms ON listings (status, rooms);

-- pg_trgm allows index use for LIKE patterns with a leading wildcard ('%минск%').
-- Without it, LOWER(city) LIKE '%..%' forces a sequential scan on every city filter.
-- NOTE: CREATE EXTENSION requires superuser or pg_extension_owner privilege.
-- On Railway the app user has sufficient rights; verify before deploying to a
-- restricted environment (e.g. run `CREATE EXTENSION pg_trgm` as superuser manually
-- and comment out that line if Flyway user lacks the privilege).
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX idx_listings_city_trgm ON listings USING gin (lower(city) gin_trgm_ops);
