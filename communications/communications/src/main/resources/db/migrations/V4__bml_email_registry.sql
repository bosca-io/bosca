-- BML email project registry (BML-SPEC-2 REQ-30).
--
-- The BML Email Template Server always renders a project's ACTIVE (latest published)
-- version; this registry is the platform-side control surface over that behavior:
-- bml_email_projects registers a project and optionally PINS the version that sends
-- render (rollback without redeploy -- the render request carries the pin).

CREATE TABLE communications.bml_email_projects (
    key            varchar PRIMARY KEY,
    description    varchar,
    -- Provenance: the git repository (git domain, by id) the project's email units live in.
    repository_id  uuid,
    -- When set, sends render THIS published artifact version instead of the active one.
    pinned_version varchar,
    created        TIMESTAMPTZ NOT NULL DEFAULT now(),
    modified       TIMESTAMPTZ NOT NULL DEFAULT now()
);
