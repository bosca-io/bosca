ALTER TABLE communications.delivery_events
    ADD COLUMN provider_event_id varchar;

CREATE UNIQUE INDEX idx_delivery_events_provider_event_id
    ON communications.delivery_events(provider_event_id)
    WHERE provider_event_id IS NOT NULL;

CREATE INDEX idx_delivery_status_updated
    ON communications.delivery_status(updated_at DESC, message_id DESC, recipient_id DESC, channel DESC);
