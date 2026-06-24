ALTER TABLE user_saved_searches
  ADD COLUMN deal_type VARCHAR(30),
  ADD COLUMN city_id   BIGINT;
