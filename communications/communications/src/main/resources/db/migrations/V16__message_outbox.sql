CREATE TABLE communications.message_outbox_batches (
    source_id   UUID PRIMARY KEY,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE communications.message_outbox (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source_id   UUID NOT NULL REFERENCES communications.message_outbox_batches(source_id) ON DELETE CASCADE,
    position    INT NOT NULL,
    message     JSONB NOT NULL,
    attempts    INT NOT NULL DEFAULT 0,
    last_error  TEXT,
    sent_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (source_id, position)
);

CREATE INDEX message_outbox_pending_idx
    ON communications.message_outbox (created_at)
    WHERE sent_at IS NULL;

CREATE FUNCTION communications.create_message_outbox(p_source_id UUID, p_messages JSONB)
RETURNS SETOF communications.message_outbox
LANGUAGE plpgsql
AS $$
BEGIN
    INSERT INTO communications.message_outbox_batches (source_id)
    VALUES (p_source_id)
    ON CONFLICT (source_id) DO NOTHING;

    IF NOT FOUND THEN
        RETURN;
    END IF;

    RETURN QUERY
        INSERT INTO communications.message_outbox (source_id, position, message)
        SELECT
            p_source_id,
            CAST(payload.ordinality - 1 AS INT),
            payload.value
        FROM jsonb_array_elements(p_messages) WITH ORDINALITY AS payload(value, ordinality)
        RETURNING *;
END;
$$;
