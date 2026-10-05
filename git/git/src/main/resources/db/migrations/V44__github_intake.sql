create table git.github_repository_pairs (
    repository_id uuid primary key references git.repositories(id) on delete cascade,
    github_repository_id bigint not null unique check (github_repository_id > 0),
    owner varchar not null,
    name varchar not null,
    webhook_secret_name varchar not null,
    token_secret_name varchar not null,
    enabled boolean not null default false,
    version bigint not null default 0,
    created timestamptz not null default now(),
    modified timestamptz not null default now()
);

create table git.github_users (
    github_user_id bigint primary key check (github_user_id > 0),
    principal_id uuid not null,
    created timestamptz not null default now(),
    modified timestamptz not null default now()
);

create table git.github_deliveries (
    delivery_id varchar primary key,
    repository_id uuid not null references git.github_repository_pairs(repository_id) on delete cascade,
    event varchar not null,
    payload jsonb not null,
    payload_digest varchar(64) not null,
    github_user_id bigint,
    principal_id uuid,
    ignored boolean not null default false,
    created timestamptz not null default now()
);
create index github_deliveries_repository_created on git.github_deliveries(repository_id, created desc);
