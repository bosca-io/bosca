create table kit.swarm_dispatch (
    id uuid primary key,
    session_id uuid not null references kit.runtime_session(session_id) on delete cascade,
    profile text not null,
    key text not null,
    request jsonb,
    materialized boolean not null default false,
    stopping boolean not null default false,
    stopped boolean not null default false,
    created timestamptz not null default now(),
    unique (profile, key)
);
