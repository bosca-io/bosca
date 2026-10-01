CREATE TYPE recommendations.training_status AS ENUM ('QUEUED', 'RUNNING', 'COMPLETED', 'FAILED');

ALTER TABLE recommendations.contexts
    ADD COLUMN selection_revision bigint NOT NULL DEFAULT 0,
    ADD COLUMN active_model_version bigint,
    ADD COLUMN requested_model_version bigint;

CREATE TABLE recommendations.context_models (
    version bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    context_id uuid NOT NULL REFERENCES recommendations.contexts(id) ON DELETE CASCADE,
    revision bigint NOT NULL,
    selection_revision bigint NOT NULL,
    context jsonb NOT NULL,
    status recommendations.training_status NOT NULL DEFAULT 'QUEUED',
    exported boolean NOT NULL DEFAULT false,
    personalized boolean NOT NULL DEFAULT false,
    pinned boolean NOT NULL DEFAULT false,
    failure text,
    created timestamptz NOT NULL DEFAULT now(),
    started timestamptz,
    completed timestamptz
);
CREATE INDEX context_models_context ON recommendations.context_models(context_id, version DESC);

CREATE TABLE recommendations.context_model_items (
    model_version bigint NOT NULL REFERENCES recommendations.context_models(version) ON DELETE CASCADE,
    metadata_id uuid NOT NULL,
    PRIMARY KEY (model_version, metadata_id)
);
