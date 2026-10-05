alter table metadata
    add column source_url varchar;

create table collection_metadata_relationships
(
    collection_id uuid not null,
    metadata_id   uuid not null,
    relationship  varchar,
    attributes    jsonb,
    primary key (collection_id, metadata_id, relationship),
    foreign key (collection_id) references collections (id) on delete cascade,
    foreign key (metadata_id) references metadata (id) on delete cascade
);