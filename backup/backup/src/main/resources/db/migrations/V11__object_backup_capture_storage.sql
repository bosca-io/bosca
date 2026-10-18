create table backup.object_capture_storage (
    key varchar(64) primary key references backup.object_capture_plans(key),
    backup_key text not null references backup.object_stores(key),
    created timestamptz not null default now()
);

create table backup.object_capture_stores (
    plan_key varchar(64) not null references backup.object_capture_storage(key),
    store text not null check (btrim(store) <> ''),
    storage_key text not null references backup.object_stores(key),
    primary key (plan_key, store)
);
