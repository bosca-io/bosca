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

alter table git.commit_statuses add column modified timestamptz not null default clock_timestamp();
alter table git.commit_statuses add column ref text;
create index commit_statuses_modified on git.commit_statuses(repository_id, modified, id);
