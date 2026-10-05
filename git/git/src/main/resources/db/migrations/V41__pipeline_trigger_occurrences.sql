create table git.pipeline_trigger_occurrences (
    trigger_id uuid not null,
    pipeline_id uuid not null references git.pipelines(id) on delete cascade,
    created timestamptz not null default now(),
    primary key (trigger_id, pipeline_id)
);
