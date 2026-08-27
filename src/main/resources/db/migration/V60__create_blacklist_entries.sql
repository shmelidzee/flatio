CREATE TABLE blacklist_entries (
  id         BIGSERIAL PRIMARY KEY,
  user_id    BIGINT                   NOT NULL REFERENCES users (id),
  type       VARCHAR(20)              NOT NULL,
  value      VARCHAR(100)             NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
  CONSTRAINT uq_blacklist_entries_user_type_value UNIQUE (user_id, type, value)
);
