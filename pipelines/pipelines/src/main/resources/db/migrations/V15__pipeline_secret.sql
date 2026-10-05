-- Node secrets (WORKOPS-SPEC-16 / REQ-132): named, AES/GCM-encrypted credentials a node references by
-- name and resolves at execution from this store — never persisted in the pipeline graph, run snapshot,
-- or any trace/log. Encrypted at rest; the encryption key comes from config/env, not the database.
create table pipelines.pipeline_secret
(
    name            text        not null primary key,
    encrypted_value text        not null,
    created_at      timestamptz not null default now(),
    modified_at     timestamptz not null default now()
);
