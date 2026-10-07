CREATE TABLE artifacts.sync_destinations (
    id UUID PRIMARY KEY,
    repository_id UUID NOT NULL REFERENCES artifacts.repositories(id) ON DELETE CASCADE,
    key TEXT NOT NULL,
    remote_repository TEXT NOT NULL,
    username TEXT NOT NULL,
    token_secret_name TEXT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT false,
    version BIGINT NOT NULL DEFAULT 0,
    created TIMESTAMPTZ NOT NULL DEFAULT now(),
    modified TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (repository_id, key)
);

CREATE TABLE artifacts.syncs (
    id UUID PRIMARY KEY,
    destination_id UUID NOT NULL REFERENCES artifacts.sync_destinations(id) ON DELETE CASCADE,
    version_id UUID NOT NULL REFERENCES artifacts.versions(id) ON DELETE CASCADE,
    tag_name TEXT NOT NULL,
    manifest_digest TEXT NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    synced TIMESTAMPTZ,
    error TEXT,
    created TIMESTAMPTZ NOT NULL DEFAULT now(),
    modified TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (destination_id, tag_name)
);
CREATE INDEX syncs_version ON artifacts.syncs(version_id);
