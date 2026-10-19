create table backup.object_restore_requests (
    key text primary key check (btrim(key) <> ''),
    run_key varchar(64) not null references backup.object_runs (key)
);
