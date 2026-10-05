create schema if not exists git;

create type git.visibility as enum ('public', 'internal', 'private');
create type git.repository_content_type as enum ('general', 'bx_project', 'script_project', 'documentation');

create table git.repositories (
    id              uuid primary key default gen_random_uuid(),
    slug            varchar not null,
    name            varchar not null,
    description     varchar,
    owner_id        uuid not null,
    owner_type      profile_type not null,
    visibility      git.visibility not null default 'private',
    default_branch  varchar not null default 'main',
    archived        boolean not null default false,
    deleted         boolean not null default false,
    deleted_at      timestamptz,
    forked_from_id  uuid references git.repositories(id) on delete set null,
    content_type    git.repository_content_type,
    disk_size_bytes bigint not null default 0,
    configuration   jsonb not null default '{}',
    next_pr_number  int not null default 1,
    created         timestamptz not null default now(),
    updated         timestamptz not null default now(),
    unique (owner_id, slug)
);
create index idx_git_repositories_owner on git.repositories (owner_id, owner_type);
create index idx_git_repositories_visibility on git.repositories (visibility) where deleted = false;

create table git.dfs_refs (
    repository_id   uuid not null references git.repositories(id) on delete cascade,
    name            varchar not null,
    object_id       varchar not null,
    peeled_id       varchar,
    symbolic_target varchar,
    created         timestamptz not null default now(),
    updated         timestamptz not null default now(),
    primary key (repository_id, name)
);

create table git.dfs_packs (
    id              uuid primary key default gen_random_uuid(),
    repository_id   uuid not null references git.repositories(id) on delete cascade,
    pack_name       varchar not null,
    pack_source     varchar not null default 'INSERT',
    file_size       bigint not null default 0,
    object_count    bigint not null default 0,
    delta_count     bigint not null default 0,
    min_update_idx  bigint not null default 0,
    max_update_idx  bigint not null default 0,
    committed       boolean not null default false,
    gc_retained     boolean not null default false,
    created         timestamptz not null default now(),
    unique (repository_id, pack_name)
);
create index idx_git_dfs_packs_repo on git.dfs_packs (repository_id) where committed = true;

create table git.dfs_pack_extensions (
    pack_id         uuid not null references git.dfs_packs(id) on delete cascade,
    extension       varchar not null,
    file_size       bigint not null default 0,
    storage_path    varchar not null,
    created         timestamptz not null default now(),
    primary key (pack_id, extension)
);

create table git.repository_permissions (
    repository_id   uuid not null references git.repositories(id) on delete cascade,
    group_id        uuid not null references groups(id) on delete cascade,
    action          permission_action not null,
    primary key (repository_id, group_id, action)
);
create index idx_git_repo_perms_group on git.repository_permissions (group_id);
