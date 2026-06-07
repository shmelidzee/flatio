-- source.country_id used in JOIN and findActiveByCountryCode queries
CREATE INDEX idx_source_country_id ON source (country_id);
-- source.active used in WHERE filter
CREATE INDEX idx_source_active ON source (active);
