create table git.lfs_objects (
    id              uuid primary key default gen_random_uuid(),
    repository_id   uuid not null references git.repositories(id) on delete cascade,
    oid             varchar not null,
    size            bigint not null,
    created         timestamptz not null default now(),
    unique (repository_id, oid)
);
create index idx_git_lfs_objects_repo on git.lfs_objects (repository_id);

create table git.deploy_tokens (
    id              uuid primary key default gen_random_uuid(),
    repository_id   uuid not null references git.repositories(id) on delete cascade,
    name            varchar not null,
    token_hash      varchar not null,
    read_only       boolean not null default true,
    last_used_at    timestamptz,
    created         timestamptz not null default now()
);
create index idx_git_deploy_tokens_repo on git.deploy_tokens (repository_id);
create unique index idx_git_deploy_tokens_hash on git.deploy_tokens (token_hash);

create table git.commit_statuses (
    id              uuid primary key default gen_random_uuid(),
    repository_id   uuid not null references git.repositories(id) on delete cascade,
    commit_sha      varchar not null,
    context         varchar not null,
    state           varchar not null,
    description     varchar,
    target_url      varchar,
    created         timestamptz not null default now()
);
create index idx_git_commit_statuses_repo_sha on git.commit_statuses (repository_id, commit_sha);
create unique index idx_git_commit_statuses_unique on git.commit_statuses (repository_id, commit_sha, context);
