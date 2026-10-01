ALTER TABLE communications.notification_types
    ADD COLUMN default_email_enabled BOOLEAN,
    ADD COLUMN default_push_enabled BOOLEAN;

UPDATE communications.notification_types
SET default_email_enabled = default_enabled,
    default_push_enabled = default_enabled;

ALTER TABLE communications.notification_types
    ALTER COLUMN default_email_enabled SET NOT NULL,
    ALTER COLUMN default_email_enabled SET DEFAULT true,
    ALTER COLUMN default_push_enabled SET NOT NULL,
    ALTER COLUMN default_push_enabled SET DEFAULT true,
    DROP CONSTRAINT notification_types_non_optional_default_enabled,
    DROP COLUMN default_enabled,
    ADD CONSTRAINT notification_types_non_optional_channel_defaults
        CHECK (optional OR (default_email_enabled AND default_push_enabled));
