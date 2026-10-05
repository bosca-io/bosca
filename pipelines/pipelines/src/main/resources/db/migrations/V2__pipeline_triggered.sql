-- Pipelines become directly triggerable: an Active flag replaces the trigger-binding hop, and runs
-- get their own history (previously only binding-keyed history existed in scripting).

alter table pipelines.pipelines
    add column triggered boolean not null default false;

create index ix_pipelines_triggered
    on pipelines.pipelines (accepted_input_type)
    where triggered and deleted_at is null;

create table pipelines.pipeline_run_log
(
    id            uuid        not null default gen_random_uuid() primary key,
    pipeline_id   uuid        not null references pipelines.pipelines (id) on delete cascade,
    event_name    varchar     not null,
    outcome       varchar     not null,
    started_at    timestamptz not null default now(),
    finished_at   timestamptz,
    duration_ms   bigint,
    error_message varchar
);

create index pipeline_run_log_pipeline_idx
    on pipelines.pipeline_run_log (pipeline_id, started_at desc);
