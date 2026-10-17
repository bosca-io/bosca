create table backup.object_run_storage (
    key varchar(64) primary key references backup.object_runs(key),
    backup_key text not null references backup.object_stores(key),
    created timestamptz not null default now()
);

create table backup.object_restore_stores (
    destination_id text not null,
    store text not null check (btrim(store) <> ''),
    storage_key text not null references backup.object_stores(key),
    primary key (destination_id, store),
    unique (destination_id, store, storage_key)
);

create table backup.object_run_stores (
    run_key varchar(64) not null references backup.object_run_storage(key),
    store text not null check (btrim(store) <> ''),
    storage_key text not null references backup.object_stores(key),
    restore_destination_id text,
    primary key (run_key, store),
    foreign key (restore_destination_id, store, storage_key)
        references backup.object_restore_stores(destination_id, store, storage_key)
);
