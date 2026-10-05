CREATE SCHEMA IF NOT EXISTS artifacts;

-- Content-addressable blob store metadata.
-- Actual bytes live in ObjectStorageService (S3/GCS/filesystem).
-- Digest format: sha256:<hex>
CREATE TABLE artifacts.blobs (
    digest      TEXT        PRIMARY KEY,
    size        BIGINT      NOT NULL,
    ref_count   INTEGER     NOT NULL DEFAULT 0,
    created     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Namespaces group artifacts and control access.
-- Examples: "library" for Docker, "com.acme" for Maven, "@acme" for npm.
CREATE TABLE artifacts.namespaces (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    name        TEXT        NOT NULL UNIQUE,
    public      BOOLEAN     NOT NULL DEFAULT false,
    created     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- A named artifact within a namespace.
-- Docker: repository name. Maven: groupId:artifactId. npm: package name.
CREATE TABLE artifacts.repositories (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    namespace_id    UUID        NOT NULL REFERENCES artifacts.namespaces(id),
    name            TEXT        NOT NULL,
    type            TEXT        NOT NULL CHECK (type IN ('docker', 'maven', 'npm')),
    created         TIMESTAMPTZ NOT NULL DEFAULT now(),
    modified        TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (namespace_id, name, type)
);

-- Versioned releases of an artifact.
CREATE TABLE artifacts.versions (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    repository_id   UUID        NOT NULL REFERENCES artifacts.repositories(id),
    version         TEXT        NOT NULL,
    metadata        JSONB,
    created         TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (repository_id, version)
);

-- Maps versions to their constituent blobs.
CREATE TABLE artifacts.version_blobs (
    version_id  UUID    NOT NULL REFERENCES artifacts.versions(id) ON DELETE CASCADE,
    digest      TEXT    NOT NULL REFERENCES artifacts.blobs(digest),
    role        TEXT    NOT NULL,
    filename    TEXT,
    media_type  TEXT,
    PRIMARY KEY (version_id, digest, role)
);

-- Docker tags: mutable pointers to manifests.
CREATE TABLE artifacts.tags (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    repository_id   UUID        NOT NULL REFERENCES artifacts.repositories(id),
    name            TEXT        NOT NULL,
    manifest_digest TEXT        NOT NULL REFERENCES artifacts.blobs(digest),
    created         TIMESTAMPTZ NOT NULL DEFAULT now(),
    modified        TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (repository_id, name)
);

-- Upload sessions for Docker chunked blob uploads.
CREATE TABLE artifacts.upload_sessions (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    repository_id   UUID        NOT NULL REFERENCES artifacts.repositories(id),
    state           TEXT        NOT NULL DEFAULT 'active' CHECK (state IN ('active', 'completed', 'cancelled')),
    byte_offset     BIGINT      NOT NULL DEFAULT 0,
    chunk_count     INTEGER     NOT NULL DEFAULT 0,
    created         TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires         TIMESTAMPTZ NOT NULL DEFAULT now() + INTERVAL '1 hour'
);

-- Indexes
CREATE INDEX idx_versions_repository ON artifacts.versions(repository_id);
CREATE INDEX idx_version_blobs_digest ON artifacts.version_blobs(digest);
CREATE INDEX idx_tags_repository ON artifacts.tags(repository_id);
CREATE INDEX idx_upload_sessions_expires ON artifacts.upload_sessions(expires) WHERE state = 'active';
CREATE INDEX idx_blobs_ref_count ON artifacts.blobs(ref_count) WHERE ref_count <= 0;
