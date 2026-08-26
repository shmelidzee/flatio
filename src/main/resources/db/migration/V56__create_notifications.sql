-- History of notifications raised for subscriptions when a matching listing change occurs
-- (FR-SUB-4, FR-SUB-8, FR-SUB-9). One row per (subscription, listing, trigger_type) combination;
-- the unique constraint below is the database-level half of the deduplication rule enforced in
-- NotificationTriggerServiceImpl before insert.
--
-- No partitioning for MVP (traffic does not warrant it yet), but nothing here hardcodes that
-- assumption in application code: the surrogate BIGSERIAL id and plain btree indexes below do not
-- prevent converting this table to a range/hash partition on created_at later if volume grows.
CREATE TABLE notifications
(
  id              BIGSERIAL                PRIMARY KEY,
  subscription_id BIGINT                   NOT NULL REFERENCES subscriptions (id) ON DELETE CASCADE,
  listing_id      BIGINT                   NOT NULL REFERENCES listings (id) ON DELETE CASCADE,
  trigger_type    VARCHAR(30)              NOT NULL,
  status          VARCHAR(10)              NOT NULL DEFAULT 'PENDING',
  sent_at         TIMESTAMP WITH TIME ZONE,
  created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
  updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

  CONSTRAINT uq_notifications_subscription_listing_trigger
    UNIQUE (subscription_id, listing_id, trigger_type)
);
