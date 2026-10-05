-- V35: Release↔Pipeline link (WORKOPS-SPEC-22 R1).
--
-- Binds a workops.release to the pipelines-engine Pipeline that builds and
-- deploys it (the release build graph is that pipeline). pipeline_id references
-- a row in the separate `pipelines` schema, so it is an unconstrained uuid here
-- (no cross-schema FK) — the pipelines engine owns that entity's lifecycle.

create table workops.release_pipeline (
    release_id  uuid        not null references workops.release(id) on delete cascade,
    pipeline_id uuid        not null,
    created_at  timestamptz not null default now(),
    primary key (release_id)
);

create index release_pipeline_pipeline_idx on workops.release_pipeline(pipeline_id);
