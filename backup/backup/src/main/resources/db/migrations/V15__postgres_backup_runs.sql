create table backup.postgres_runs (
    key varchar(64) primary key check (key ~ '^[a-zA-Z0-9_-]{1,64}$'),
    source_id text not null check (btrim(source_id) <> ''),
    storage_key text not null references backup.object_stores(key),
    prefix text not null check (btrim(prefix) <> ''),
    status text not null default 'pending' check (status in ('pending', 'running', 'completed', 'failed', 'cancelled')),
    attempts integer not null default 0 check (attempts >= 0),
    writer_token text,
    error text,
    result jsonb check (jsonb_typeof(result) = 'object'),
    version bigint not null default 0 check (version >= 0),
    created timestamptz not null default now(),
    modified timestamptz not null default now(),
    check ((status = 'running') = (writer_token is not null)),
    check (writer_token is null or btrim(writer_token) <> ''),
    check ((status = 'completed') = (result is not null)),
    check ((status in ('failed', 'cancelled')) = (error is not null))
);
create index postgres_runs_scope on backup.postgres_runs(source_id, storage_key, created desc, key);

alter table backup.object_store_writers
    drop constraint object_store_writer_owner,
    add column postgres_key varchar(64) unique references backup.postgres_runs(key),
    add constraint object_store_writer_owner check (
        (run_key is not null and capture_key is null and capture_token is null and postgres_key is null)
        or
        (run_key is null and capture_key is not null and capture_token is not null
            and btrim(capture_token) <> '' and postgres_key is null)
        or
        (run_key is null and capture_key is null and capture_token is null and postgres_key is not null)
    );
