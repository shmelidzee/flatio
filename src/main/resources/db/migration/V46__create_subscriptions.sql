CREATE TABLE subscriptions (
  id                    BIGSERIAL PRIMARY KEY,
  user_id               BIGINT                   NOT NULL REFERENCES users (id),
  name                  VARCHAR(255)             NOT NULL,
  is_active             BOOLEAN                  NOT NULL DEFAULT TRUE,
  search_criteria       JSONB                    NOT NULL,
  delivery_mode         VARCHAR(20)              NOT NULL,
  channel_type          VARCHAR(20)              NOT NULL,
  price_drop_threshold  NUMERIC(5, 2)                     DEFAULT 5.00,
  quiet_hours_start     TIME,
  quiet_hours_end       TIME,
  created_at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
  updated_at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE TABLE subscription_triggers (
  subscription_id BIGINT      NOT NULL REFERENCES subscriptions (id) ON DELETE CASCADE,
  trigger_type    VARCHAR(30) NOT NULL,
  PRIMARY KEY (subscription_id, trigger_type)
);
