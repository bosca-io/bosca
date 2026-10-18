create table backup.object_capture_plans (
    key varchar(64) primary key,
    destination_id text not null check (btrim(destination_id) <> ''),
    inventory jsonb not null check (jsonb_typeof(inventory) = 'object'),
    payload_prefix text not null check (btrim(payload_prefix) <> ''),
    path text not null check (btrim(path) <> ''),
    created timestamptz not null default now()
);

create index object_capture_plans_scope on backup.object_capture_plans (
    (inventory->>'sourceId'), destination_id, (inventory->>'scope'), created desc, key
);
