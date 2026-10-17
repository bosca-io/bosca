create table backup.object_run_dispatches (
    key uuid primary key,
    run_key varchar(64) not null references backup.object_runs(key),
    version bigint not null check (version >= 0),
    created timestamptz not null default now(),
    unique (run_key, version)
);
