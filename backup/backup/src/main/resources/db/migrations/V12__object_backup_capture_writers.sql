alter table backup.object_store_writers
    alter column run_key drop not null,
    add column capture_key varchar(64) references backup.object_capture_plans(key),
    add column capture_token text,
    add constraint object_store_writer_owner check (
        (run_key is not null and capture_key is null and capture_token is null)
        or
        (run_key is null and capture_key is not null and capture_token is not null and btrim(capture_token) <> '')
    ),
    add constraint object_store_writer_capture unique (capture_key);
