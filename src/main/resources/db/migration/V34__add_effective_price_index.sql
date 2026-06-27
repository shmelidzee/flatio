-- V6 created idx_listing_price on the plain price column. After PR #242 added price_byn,
-- ListingServiceImpl and fullTextSearch() filter on COALESCE(price_byn, price). PostgreSQL
-- cannot use a B-tree index on a column wrapped in a function, so every price-range query
-- degrades to a sequential scan. This functional index restores range query performance.
CREATE INDEX idx_listings_effective_price ON listings (COALESCE(price_byn, price));
