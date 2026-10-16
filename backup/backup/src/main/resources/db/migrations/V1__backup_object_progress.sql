create schema if not exists backup;

create table backup.object_progress (
    key varchar(64) primary key,
    source_id text not null,
    destination_id text not null,
    scope text not null,
    payload jsonb not null check (jsonb_typeof(payload) = 'object'),
    status text not null default 'pending' check (status in ('pending', 'running', 'completed', 'failed')),
    attempts integer not null default 0 check (attempts >= 0),
    verified_bytes bigint not null default 0 check (verified_bytes >= 0),
    error text,
    version bigint not null default 0 check (version >= 0),
    created timestamptz not null default now(),
    modified timestamptz not null default now(),
    check (status <> 'completed' or verified_bytes = (payload->>'length')::bigint),
    check (status = 'completed' or verified_bytes = 0),
    check ((status = 'failed') = (error is not null))
);

create index object_progress_scope on backup.object_progress (source_id, destination_id, scope, created, key);
