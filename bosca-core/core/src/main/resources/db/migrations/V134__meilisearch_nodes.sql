create table meilisearch_nodes (
    id uuid not null default gen_random_uuid(),
    name varchar not null,
    description varchar not null default '',
    url varchar not null,
    api_key varchar not null,
    types storage_system_type[] not null default '{search}',
    configuration jsonb not null default '{}'::jsonb,
    primary key (id)
);

create table storage_system_nodes (
    storage_system_id uuid not null,
    node_id uuid not null,
    primary key (storage_system_id, node_id),
    foreign key (storage_system_id) references storage_systems (id) on delete cascade,
    foreign key (node_id) references meilisearch_nodes (id) on delete cascade
);
