-- Message channel enum.
CREATE TYPE communications.channel AS ENUM ('EMAIL', 'PUSH');

-- Delivery status lifecycle enum.
CREATE TYPE communications.delivery_status_type AS ENUM (
    'PENDING', 'SENT', 'DELIVERED', 'DEFERRED', 'BOUNCED',
    'DROPPED', 'OPENED', 'CLICKED', 'SPAM_REPORT', 'UNSUBSCRIBED', 'FAILED'
);

-- Delivery event log (append-only).
-- Stores per-recipient delivery events from SendGrid webhooks
-- and internal state transitions.

CREATE TABLE communications.delivery_events (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    message_id      UUID NOT NULL,
    recipient_id    UUID NOT NULL,
    channel         communications.channel NOT NULL DEFAULT 'EMAIL',
    status          communications.delivery_status_type NOT NULL,
    provider_event  varchar,
    error_code      varchar,
    error_message   varchar,
    metadata        JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_delivery_events_message ON communications.delivery_events(message_id);
CREATE INDEX idx_delivery_events_recipient ON communications.delivery_events(recipient_id);
CREATE INDEX idx_delivery_events_status ON communications.delivery_events(status);
CREATE INDEX idx_delivery_events_created ON communications.delivery_events(created_at);

-- Aggregate delivery status per message+recipient (materialized from events).
-- Updated on each new event — the latest status wins.

CREATE TABLE communications.delivery_status (
    message_id      UUID NOT NULL,
    recipient_id    UUID NOT NULL,
    channel         communications.channel NOT NULL DEFAULT 'EMAIL',
    status          communications.delivery_status_type NOT NULL DEFAULT 'PENDING',
    attempts        INT NOT NULL DEFAULT 0,
    last_attempt_at TIMESTAMPTZ,
    delivered_at    TIMESTAMPTZ,
    bounced_at      TIMESTAMPTZ,
    opened_at       TIMESTAMPTZ,
    clicked_at      TIMESTAMPTZ,
    error_code      varchar,
    error_message   varchar,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (message_id, recipient_id, channel)
);

CREATE INDEX idx_delivery_status_recipient ON communications.delivery_status(recipient_id);
CREATE INDEX idx_delivery_status_status ON communications.delivery_status(status);

-- Per-user email preference management.
-- Controls which email categories a user has opted in/out of.

CREATE TABLE communications.email_preferences (
    profile_id          UUID NOT NULL,
    category            varchar NOT NULL,
    opted_out           BOOLEAN NOT NULL DEFAULT false,
    unsubscribe_token   varchar,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (profile_id, category)
);

CREATE INDEX idx_email_preferences_profile ON communications.email_preferences(profile_id);

-- Suppression list for hard-bounced addresses.
-- Addresses here are excluded from future sends.

CREATE TABLE communications.suppression_list (
    email           varchar PRIMARY KEY,
    reason          varchar NOT NULL,
    provider_code   varchar,
    suppressed_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Email event → BX template mapping.
-- Maps event keys (e.g. "email-verification", "welcome") to the
-- BX project + document that renders them.

CREATE TABLE communications.email_event_templates (
    event_key       varchar PRIMARY KEY,
    project         varchar NOT NULL,
    document_id     varchar NOT NULL,
    description     varchar,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
