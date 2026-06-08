-- Extends deal_type column from VARCHAR(10) to VARCHAR(20)
-- to accommodate RENT_DAILY (10 chars) and future deal types.
ALTER TABLE listings ALTER COLUMN deal_type TYPE VARCHAR(20);
