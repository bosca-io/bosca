create table git.pipeline_evaluations (
    id uuid primary key,
    repository_id uuid not null references git.repositories(id) on delete cascade,
    commit_sha varchar(40) not null,
    ref text not null,
    completed boolean not null default false,
    error text,
    selections jsonb not null default '[]',
    modified timestamptz not null default clock_timestamp(),
    created timestamptz not null default now()
);
create index pipeline_evaluations_commit on git.pipeline_evaluations(repository_id, commit_sha);

alter table git.pipeline_runs add column rerun_of uuid references git.pipeline_runs(id) on delete set null;
create sequence git.pipeline_run_attempts;
alter table git.pipeline_runs add column attempt_order bigint;
with ordered as materialized (
    select id, nextval('git.pipeline_run_attempts') as attempt_order
    from git.pipeline_runs order by created, number, id
)
update git.pipeline_runs r set attempt_order = o.attempt_order from ordered o where r.id = o.id;
alter table git.pipeline_runs alter column attempt_order set not null;
alter table git.pipeline_runs alter column attempt_order set default nextval('git.pipeline_run_attempts');
create index pipeline_runs_rerun on git.pipeline_runs(rerun_of, attempt_order desc);

create index pipeline_runs_commit on git.pipeline_runs(repository_id, commit_sha, number, id);

alter table git.pipeline_runs add column modified timestamptz not null default clock_timestamp();
update git.pipeline_runs set modified = coalesce(finished, started, created);
create index pipeline_runs_modified on git.pipeline_runs(repository_id, modified, id);
create index pipeline_evaluations_modified on git.pipeline_evaluations(repository_id, modified, id);
