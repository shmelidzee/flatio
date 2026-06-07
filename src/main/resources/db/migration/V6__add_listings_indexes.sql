-- source_id used in JOIN and findBySource queries
CREATE INDEX idx_listing_source_id ON listings (source_id);

-- status used in WHERE filter (active listings)
CREATE INDEX idx_listing_status ON listings (status);

-- deal_type used in WHERE filter (rent vs sell)
CREATE INDEX idx_listing_deal_type ON listings (deal_type);

-- price used in range queries
CREATE INDEX idx_listing_price ON listings (price);

-- published_at used in ORDER BY and range queries
CREATE INDEX idx_listing_published_at ON listings (published_at);
