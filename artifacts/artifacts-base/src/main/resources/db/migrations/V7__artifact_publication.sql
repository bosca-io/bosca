ALTER TABLE artifacts.versions ADD COLUMN finalized BOOLEAN NOT NULL DEFAULT false;

CREATE TABLE artifacts.publication_destinations (
    id UUID PRIMARY KEY,
    repository_id UUID NOT NULL REFERENCES artifacts.repositories(id) ON DELETE CASCADE,
    key TEXT NOT NULL,
    github_repository_id BIGINT NOT NULL CHECK (github_repository_id > 0),
    owner TEXT NOT NULL,
    github_repository TEXT NOT NULL,
    tag_prefix TEXT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT false,
    token_secret_name TEXT NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created TIMESTAMPTZ NOT NULL DEFAULT now(),
    modified TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (repository_id, key)
);

CREATE TABLE artifacts.publications (
    id UUID PRIMARY KEY,
    destination_id UUID NOT NULL REFERENCES artifacts.publication_destinations(id) ON DELETE CASCADE,
    version_id UUID NOT NULL REFERENCES artifacts.versions(id) ON DELETE CASCADE,
    tag_name TEXT NOT NULL,
    commit_sha TEXT NOT NULL,
    prerelease BOOLEAN NOT NULL,
    files JSONB NOT NULL,
    release_id BIGINT,
    attempts INTEGER NOT NULL DEFAULT 0,
    published TIMESTAMPTZ,
    verified TIMESTAMPTZ,
    error TEXT,
    created TIMESTAMPTZ NOT NULL DEFAULT now(),
    modified TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (destination_id, version_id),
    UNIQUE (destination_id, tag_name)
);
CREATE INDEX publications_version ON artifacts.publications(version_id);
