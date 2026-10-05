alter table metadata add column sync_variant_collections boolean default true not null;
alter table metadata add column sync_variant_relationships boolean default true not null;
alter table metadata add column searchable boolean default true not null;

alter table collections add column searchable boolean default true not null;