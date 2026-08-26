CREATE TABLE favorites (
  id           BIGSERIAL PRIMARY KEY,
  user_id      BIGINT                   NOT NULL REFERENCES users (id),
  listing_id   BIGINT                   NOT NULL REFERENCES listings (id),
  price_at_add NUMERIC(15, 2),
  created_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
  CONSTRAINT uq_favorites_user_listing UNIQUE (user_id, listing_id)
);
