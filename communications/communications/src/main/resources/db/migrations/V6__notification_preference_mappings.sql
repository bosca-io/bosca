-- Channel-scoped external ownership of notification preferences. A mapped cell is read from and
-- written to its provider on demand; cells without mappings remain in notification_preferences.

CREATE TABLE communications.notification_preference_mappings (
    type        varchar NOT NULL REFERENCES communications.notification_types(key) ON DELETE CASCADE,
    channel     communications.channel NOT NULL,
    provider    varchar NOT NULL,
    external_id varchar(255) NOT NULL,
    created     TIMESTAMPTZ NOT NULL DEFAULT now(),
    modified    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (type, channel),
    UNIQUE (provider, channel, external_id)
);
