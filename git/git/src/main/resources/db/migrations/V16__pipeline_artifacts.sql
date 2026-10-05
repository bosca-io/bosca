create table git.pipeline_artifacts (
    id              uuid primary key default gen_random_uuid(),
    repository_id   uuid not null references git.repositories(id) on delete cascade,
    pipeline_run_id uuid not null references git.pipeline_runs(id) on delete cascade,
    run_number      int not null,
    name            varchar not null,
    size_bytes      bigint not null default 0,
    created         timestamptz not null default now(),
    unique (repository_id, run_number, name)
);
create index idx_git_pipeline_artifacts_run on git.pipeline_artifacts (pipeline_run_id);
