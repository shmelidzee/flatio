CREATE TABLE admin_audit_log (
  id          BIGSERIAL PRIMARY KEY,
  admin_id    BIGINT                   NOT NULL REFERENCES users (id),
  action      VARCHAR(100)             NOT NULL,
  object_type VARCHAR(50)              NOT NULL,
  object_id   VARCHAR(100),
  created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
  updated_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);
