CREATE TABLE user_saved_searches (
  id               BIGSERIAL PRIMARY KEY,
  telegram_user_id BIGINT                      NOT NULL,
  region_code      VARCHAR(20),
  price_min        NUMERIC(15, 2),
  price_max        NUMERIC(15, 2),
  rooms_min        INTEGER,
  rooms_max        INTEGER,
  property_type    VARCHAR(50),
  is_owner         BOOLEAN,
  keyword          VARCHAR(500),
  created_at       TIMESTAMP WITH TIME ZONE    NOT NULL DEFAULT NOW(),
  updated_at       TIMESTAMP WITH TIME ZONE    NOT NULL DEFAULT NOW(),
  CONSTRAINT uq_user_saved_searches_telegram_user_id UNIQUE (telegram_user_id)
);
