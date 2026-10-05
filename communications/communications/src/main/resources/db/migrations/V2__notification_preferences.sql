-- Admin-definable notification types and channel-aware preferences.
-- Replaces the email-only communications.email_preferences model with a
-- (profile, channel, type) matrix so push notifications can be opted
-- out of independently of email, and moves the type catalog into a
-- table so admins can define new types without a code deploy.
-- Existing email preferences are migrated as EMAIL-channel rows; the
-- old table is retained until all readers have moved to the new model.

-- The notification type catalog. `key` is the immutable contract that
-- sending code references; `name`/`description` are presentation.
-- `optional = false` types always deliver and cannot be opted out.
-- `system = true` rows are seeded and cannot be deleted, and their
-- `optional` flag cannot be changed.

CREATE TABLE communications.notification_types (
    key           varchar PRIMARY KEY,
    name          varchar NOT NULL,
    description   varchar,
    optional      BOOLEAN NOT NULL DEFAULT true,
    system        BOOLEAN NOT NULL DEFAULT false,
    display_order INT NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO communications.notification_types (key, name, description, optional, system, display_order) VALUES
    ('transactional', 'Account activity', 'Essential messages about your account: receipts, confirmations, and required notices. Always delivered.', false, true, 0),
    ('security', 'Security alerts', 'Sign-in alerts, password changes, and other security events. Always delivered.', false, true, 1),
    ('digest', 'Digests', 'Periodic summaries of activity you follow.', true, true, 2),
    ('marketing', 'Product news & offers', 'Announcements, tips, and promotional messages.', true, true, 3);

-- The legacy table accepted arbitrary category strings; carry any
-- non-seeded values forward as deletable custom types so no stored
-- opt-out loses its meaning.

INSERT INTO communications.notification_types (key, name, optional, system, display_order)
SELECT DISTINCT category, initcap(category), true, false, 100
FROM communications.email_preferences
WHERE category NOT IN (SELECT key FROM communications.notification_types);

-- Channel-aware notification preferences. Rows are stored sparsely:
-- a missing (profile, channel, type) row means opted-in.

CREATE TABLE communications.notification_preferences (
    profile_id  UUID NOT NULL,
    channel     communications.channel NOT NULL,
    type        varchar NOT NULL,
    opted_out   BOOLEAN NOT NULL DEFAULT false,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (profile_id, channel, type)
);

CREATE INDEX idx_notification_preferences_profile ON communications.notification_preferences(profile_id);

-- Per-profile notification settings: quiet hours for push delivery.
-- Times are HH:MM strings in the profile's local time; time_zone is
-- an IANA zone id. A missing row (or missing time_zone) means no
-- quiet hours are applied.

CREATE TABLE communications.notification_settings (
    profile_id      UUID PRIMARY KEY,
    time_zone       varchar,
    dnd_start_local varchar,
    dnd_end_local   varchar,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Unsubscribe tokens, decoupled from preference rows so a token can
-- authenticate a profile's manage-preferences page (type IS NULL) or
-- one-click unsubscribe a single type (type set).

CREATE TABLE communications.unsubscribe_tokens (
    token       varchar PRIMARY KEY,
    profile_id  UUID NOT NULL,
    type        varchar,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_unsubscribe_tokens_profile ON communications.unsubscribe_tokens(profile_id);

-- Migrate existing email preferences as EMAIL-channel rows,
-- preserving opt-out state and timestamps exactly.

INSERT INTO communications.notification_preferences (profile_id, channel, type, opted_out, updated_at)
SELECT profile_id, 'EMAIL'::communications.channel, category, opted_out, updated_at
FROM communications.email_preferences;

-- Preserve existing unsubscribe tokens with their type scope.

INSERT INTO communications.unsubscribe_tokens (token, profile_id, type, created_at)
SELECT unsubscribe_token, profile_id, category, updated_at
FROM communications.email_preferences
WHERE unsubscribe_token IS NOT NULL;
