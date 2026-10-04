alter table git.pull_requests add column version bigint not null default 0;

create table git.github_pull_request_states (
    id uuid primary key,
    repository_id uuid not null references git.github_repository_pairs(repository_id) on delete cascade,
    pull_request_id uuid references git.pull_requests(id) on delete cascade,
    github_id bigint,
    github_number int,
    imported boolean not null default false,
    snapshot jsonb,
    pending jsonb,
    bosca jsonb,
    github jsonb,
    problem text,
    modified timestamptz not null default now(),
    unique (repository_id, pull_request_id),
    unique (repository_id, github_number),
    unique (github_id),
    check (pull_request_id is not null or github_number is not null),
    check ((github_number is null) = (github_id is null))
);
