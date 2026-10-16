create table backup.object_runs (
    key varchar(64) primary key,
    source_id text not null,
    destination_id text not null,
    scope text not null,
    inventory jsonb not null check (jsonb_typeof(inventory) = 'object'),
    path text not null,
    status text not null default 'pending'
        check (status in ('pending', 'running', 'completed', 'partial', 'failed', 'cancelled')),
    attempts integer not null default 0 check (attempts >= 0),
    error text,
    result jsonb check (jsonb_typeof(result) = 'object'),
    version bigint not null default 0 check (version >= 0),
    created timestamptz not null default now(),
    modified timestamptz not null default now(),
    check ((status in ('partial', 'failed', 'cancelled')) = (error is not null)),
    check ((status in ('completed', 'partial', 'failed', 'cancelled')) = (result is not null))
);

create index object_runs_scope on backup.object_runs (source_id, destination_id, scope, created desc, key);

create table backup.object_run_items (
    run_key varchar(64) not null references backup.object_runs (key) on delete cascade,
    position integer not null check (position >= 0),
    progress_key varchar(64) not null references backup.object_progress (key),
    primary key (run_key, position),
    unique (run_key, progress_key)
);

create index object_run_items_progress on backup.object_run_items (progress_key);
