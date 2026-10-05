create table git.github_ref_states (
    repository_id uuid not null references git.github_repository_pairs(repository_id) on delete cascade,
    ref text not null,
    sha text,
    synchronized boolean not null default false,
    bosca_sha text,
    github_sha text,
    conflict boolean not null default false,
    unattributed_before_sha text,
    unattributed_ref_modified timestamptz,
    modified timestamptz not null default now(),
    primary key (repository_id, ref)
);

create table git.github_push_results (
    delivery_id text primary key references git.github_deliveries(delivery_id) on delete cascade,
    result text not null check (result in ('APPLIED', 'UNCHANGED', 'STALE', 'CONFLICT', 'IGNORED'))
);
