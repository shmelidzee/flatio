ALTER TABLE users
  ADD COLUMN last_seen TIMESTAMPTZ;

ALTER TABLE user_auth_provider
  ADD COLUMN telegram_username VARCHAR(255);
