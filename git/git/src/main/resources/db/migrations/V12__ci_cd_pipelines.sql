-- CI/CD pipeline infrastructure: agents, pipelines, runs, jobs, steps, and secrets.
-- Logs are stored in ObjectStorageService (S3), not in PostgreSQL.

create type git.agent_mode as enum ('runner', 'orchestrator');
create type git.agent_status as enum ('online', 'offline', 'busy', 'draining');
create type git.pipeline_run_status as enum ('queued', 'running', 'success', 'failure', 'cancelled', 'skipped');
create type git.pipeline_trigger_type as enum ('push', 'pull_request', 'tag', 'manual', 'schedule');

-- Agent registration (persistent + transient)
create table git.pipeline_agents (
    id               uuid primary key default gen_random_uuid(),
    name             varchar not null,
    labels           varchar[] not null default '{default}',
    mode             git.agent_mode not null default 'runner',
    status           git.agent_status not null default 'offline',
    ephemeral        boolean not null default false,
    job_id           uuid,
    parent_agent_id  uuid references git.pipeline_agents(id) on delete set null,
    token_hash       varchar not null,
    provider_config  jsonb,
    instance_id      varchar,
    last_heartbeat   timestamptz,
    expires_at       timestamptz,
    created          timestamptz not null default now(),
    unique (name) deferrable initially deferred
);
create index idx_git_pipeline_agents_ephemeral
    on git.pipeline_agents (expires_at)
    where ephemeral = true and status != 'offline';

-- Pipeline definitions (parsed from YAML, one per file per repo)
create table git.pipelines (
    id               uuid primary key default gen_random_uuid(),
    repository_id    uuid not null references git.repositories(id) on delete cascade,
    file_path        varchar not null,
    name             varchar not null,
    triggers         jsonb not null default '[]',
    concurrency      jsonb,
    config_hash      varchar not null,
    created          timestamptz not null default now(),
    updated          timestamptz not null default now(),
    unique (repository_id, file_path)
);
create index idx_git_pipelines_repo on git.pipelines (repository_id);

-- Pipeline runs (one per trigger event)
create table git.pipeline_runs (
    id                 uuid primary key default gen_random_uuid(),
    pipeline_id        uuid not null references git.pipelines(id) on delete cascade,
    repository_id      uuid not null references git.repositories(id) on delete cascade,
    commit_sha         varchar not null,
    ref                varchar not null,
    trigger_type       git.pipeline_trigger_type not null,
    triggered_by       uuid,
    status             git.pipeline_run_status not null default 'queued',
    number             int not null,
    concurrency_group  varchar,
    created            timestamptz not null default now(),
    started            timestamptz,
    finished           timestamptz
);
create index idx_git_pipeline_runs_pipeline on git.pipeline_runs (pipeline_id, number desc);
create index idx_git_pipeline_runs_repo on git.pipeline_runs (repository_id, created desc);
create index idx_git_pipeline_runs_status on git.pipeline_runs (status) where status in ('queued', 'running');
create index idx_git_pipeline_runs_concurrency on git.pipeline_runs (concurrency_group, status)
    where concurrency_group is not null and status in ('queued', 'running');

-- Jobs within a pipeline run
create table git.pipeline_jobs (
    id               uuid primary key default gen_random_uuid(),
    pipeline_run_id  uuid not null references git.pipeline_runs(id) on delete cascade,
    name             varchar not null,
    status           git.pipeline_run_status not null default 'queued',
    runner_label     varchar not null default 'default',
    agent_id         uuid references git.pipeline_agents(id) on delete set null,
    matrix_values    jsonb not null default '{}',
    depends_on       varchar[] not null default '{}',
    created          timestamptz not null default now(),
    started          timestamptz,
    finished         timestamptz
);
create index idx_git_pipeline_jobs_run on git.pipeline_jobs (pipeline_run_id);
create index idx_git_pipeline_jobs_agent on git.pipeline_jobs (agent_id) where status = 'running';
create index idx_git_pipeline_jobs_queued on git.pipeline_jobs (runner_label, status) where status = 'queued';

-- Steps within a job
create table git.pipeline_steps (
    id               uuid primary key default gen_random_uuid(),
    pipeline_job_id  uuid not null references git.pipeline_jobs(id) on delete cascade,
    name             varchar not null,
    ordinal          int not null,
    status           git.pipeline_run_status not null default 'queued',
    exit_code        int,
    started          timestamptz,
    finished         timestamptz
);
create index idx_git_pipeline_steps_job on git.pipeline_steps (pipeline_job_id, ordinal);

-- Encrypted secrets per repository
create table git.pipeline_secrets (
    id               uuid primary key default gen_random_uuid(),
    repository_id    uuid not null references git.repositories(id) on delete cascade,
    name             varchar not null,
    encrypted_value  text not null,
    created          timestamptz not null default now(),
    updated          timestamptz not null default now(),
    unique (repository_id, name)
);

-- Add job_id FK after pipeline_jobs table exists
alter table git.pipeline_agents
    add constraint fk_pipeline_agents_job
    foreign key (job_id) references git.pipeline_jobs(id) on delete set null;
