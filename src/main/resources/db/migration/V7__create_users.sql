CREATE TABLE users
(
  id           BIGSERIAL    PRIMARY KEY,
  display_name VARCHAR(255) NOT NULL,
  email        VARCHAR(255),
  active       BOOLEAN      NOT NULL DEFAULT TRUE,
  created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE user_auth_provider
(
  id          BIGSERIAL    PRIMARY KEY,
  user_id     BIGINT       NOT NULL REFERENCES users (id),
  provider    VARCHAR(20)  NOT NULL,
  external_id VARCHAR(255) NOT NULL,
  created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  CONSTRAINT uq_provider_external_id UNIQUE (provider, external_id)
);
