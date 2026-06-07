CREATE TABLE price_history
(
  id          BIGSERIAL      PRIMARY KEY,
  listing_id  BIGINT         NOT NULL REFERENCES listings (id),
  price       NUMERIC(15, 2) NOT NULL,
  currency_id BIGINT         NOT NULL REFERENCES currency (id),
  recorded_at TIMESTAMPTZ    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_price_history_listing_recorded
  ON price_history (listing_id, recorded_at DESC);
