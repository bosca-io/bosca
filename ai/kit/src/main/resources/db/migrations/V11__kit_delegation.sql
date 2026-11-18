create table kit.delegation (
    id uuid primary key,
    parent_session_id uuid not null,
    parent_turn_id uuid not null,
    root_turn_id uuid not null references kit.runtime_turn(id) on delete cascade,
    child_session_id uuid not null unique,
    child_turn_id uuid not null unique,
    depth integer not null check (depth between 1 and 4),
    input jsonb not null,
    policy jsonb not null,
    authority jsonb not null,
    context_manifest jsonb,
    status text not null default 'QUEUED' check (status in ('QUEUED','RUNNING','COMPLETED','FAILED','CANCELLED','TIMED_OUT','INTERRUPTED')),
    result jsonb,
    failure text,
    waiting boolean not null default false,
    wait_turn_id uuid,
    deadline timestamptz not null,
    created timestamptz not null default now(),
    modified timestamptz not null default now()
);
create index delegation_parent on kit.delegation(parent_session_id);
create index delegation_root on kit.delegation(root_turn_id);
create index delegation_wait on kit.delegation(wait_turn_id) where waiting=true and status in ('QUEUED','RUNNING');
