create table kit.runtime_session (
    session_id uuid primary key,
    principal_id uuid not null,
    configuration jsonb not null,
    authority jsonb not null,
    state text not null default 'STOPPED' check (state in ('STOPPED','STARTING','READY','BUSY','WAITING_FOR_APPROVAL','STOPPING','FAILED')),
    generation bigint not null default 0,
    epoch bigint not null default 0,
    needs_context boolean not null default true,
    owner uuid,
    lease_until timestamptz,
    dispatch_id uuid,
    endpoint text,
    secrets jsonb,
    credential_id bigint,
    native_session_id text,
    active_turn uuid,
    idle_at timestamptz not null,
    archive_key text,
    archive_sha256 text,
    archive_size bigint,
    failure text,
    deleted_at timestamptz,
    version bigint not null default 0,
    created timestamptz not null default now(),
    modified timestamptz not null default now()
);
create table kit.runtime_turn (
    id uuid primary key,
    session_id uuid not null references kit.runtime_session(session_id),
    sequence bigint generated always as identity,
    message jsonb not null,
    status text not null default 'QUEUED' check (status in ('QUEUED','SENT','COMPLETED','INTERRUPTED','FAILED')),
    result jsonb,
    created timestamptz not null default now()
);
create index runtime_turn_queue on kit.runtime_turn(session_id, sequence) where status = 'QUEUED';
create index runtime_session_reconcile on kit.runtime_session(lease_until, idle_at);
create table kit.runtime_approval (
    id uuid primary key,
    session_id uuid not null references kit.runtime_session(session_id),
    turn_id uuid not null references kit.runtime_turn(id),
    request_id text not null,
    request jsonb not null,
    decision text,
    expires timestamptz not null,
    unique(turn_id, request_id)
);
