-- idx_notifications_subscription_id is a leftmost-prefix duplicate of the composite unique
-- constraint's index (subscription_id, listing_id, trigger_type) added in V56, but is kept as its
-- own index per the M2.3.1 acceptance criteria and so it survives unaffected if that constraint
-- is ever relaxed or dropped.
CREATE INDEX idx_notifications_subscription_id ON notifications (subscription_id);
CREATE INDEX idx_notifications_status ON notifications (status);
CREATE INDEX idx_notifications_created_at ON notifications (created_at);
