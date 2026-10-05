ALTER TABLE communications.notification_types
    ADD COLUMN default_enabled BOOLEAN NOT NULL DEFAULT true;

ALTER TABLE communications.notification_types
    ADD CONSTRAINT notification_types_non_optional_default_enabled
        CHECK (optional OR default_enabled);
