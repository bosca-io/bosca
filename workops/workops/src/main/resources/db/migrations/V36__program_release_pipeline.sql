-- V36: Re-key the release-relay pipeline link from RELEASE to PROGRAM (WORKOPS-SPEC-22/23).
--
-- The relay is no longer a graph generated per release. Instead a program is associated with one
-- reusable, authored pipeline, and launching a release starts a run of that pipeline with the release
-- as input. The old release-keyed rows (V35) are obsolete, so drop and recreate the link table keyed by
-- program. pipeline_id still references the separate `pipelines` schema (no cross-schema FK).

drop table if exists workops.release_pipeline;

create table workops.release_pipeline (
    program_id  uuid        not null references workops.program(id) on delete cascade,
    pipeline_id uuid        not null,
    created_at  timestamptz not null default now(),
    primary key (program_id)
);

create index release_pipeline_pipeline_idx on workops.release_pipeline(pipeline_id);
